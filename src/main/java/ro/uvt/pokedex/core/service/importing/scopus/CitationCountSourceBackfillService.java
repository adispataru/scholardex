package ro.uvt.pokedex.core.service.importing.scopus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * H131 — stamps {@link ScholardexPublicationFact#getCitedByCountScopus()} / {@code citedByCountOpenAlex} on the existing
 * canonical publications from the source facts they are linked to, without re-canonicalising anything (the OpenAlex
 * canonicalize endpoint is insert-based and fails on a live corpus; the full rebuild takes ~90 min). Only the two
 * per-source fields are written; the max scalar {@code citedByCount} is left alone. Idempotent — safe to rerun.
 */
@Service
public class CitationCountSourceBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CitationCountSourceBackfillService.class);
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final String SOURCE_OPENALEX = "OPENALEX";
    private static final int PAGE_SIZE = 2_000;

    private final MongoTemplate mongoTemplate;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final ScopusPublicationFactRepository scopusPublicationFactRepository;
    private final OpenAlexPublicationFactRepository openAlexPublicationFactRepository;

    public CitationCountSourceBackfillService(MongoTemplate mongoTemplate,
                                              ScholardexSourceLinkRepository sourceLinkRepository,
                                              ScopusPublicationFactRepository scopusPublicationFactRepository,
                                              OpenAlexPublicationFactRepository openAlexPublicationFactRepository) {
        this.mongoTemplate = mongoTemplate;
        this.sourceLinkRepository = sourceLinkRepository;
        this.scopusPublicationFactRepository = scopusPublicationFactRepository;
        this.openAlexPublicationFactRepository = openAlexPublicationFactRepository;
    }

    public record Result(long scanned, long scopusSet, long openAlexSet, long updated) {
    }

    public Result run() {
        long started = System.nanoTime();
        long scanned = 0;
        long scopusSet = 0;
        long openAlexSet = 0;
        long updated = 0;
        String lastId = null;
        while (true) {
            Query page = new Query();
            if (lastId != null) {
                page.addCriteria(Criteria.where("_id").gt(lastId));
            }
            page.with(org.springframework.data.domain.Sort.by("_id")).limit(PAGE_SIZE);
            page.fields().include("citedByCountScopus").include("citedByCountOpenAlex");
            List<ScholardexPublicationFact> facts = mongoTemplate.find(page, ScholardexPublicationFact.class);
            if (facts.isEmpty()) {
                break;
            }
            lastId = facts.get(facts.size() - 1).getId();
            scanned += facts.size();

            List<String> ids = facts.stream().map(ScholardexPublicationFact::getId).toList();
            Map<String, Integer> scopusCounts = scopusCounts(ids);
            Map<String, Integer> openAlexCounts = openAlexCounts(ids);

            BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, ScholardexPublicationFact.class);
            int pending = 0;
            for (ScholardexPublicationFact fact : facts) {
                Integer scopus = scopusCounts.get(fact.getId());
                Integer openAlex = openAlexCounts.get(fact.getId());
                if (scopus != null) {
                    scopusSet++;
                }
                if (openAlex != null) {
                    openAlexSet++;
                }
                if (Objects.equals(scopus, fact.getCitedByCountScopus())
                        && Objects.equals(openAlex, fact.getCitedByCountOpenAlex())) {
                    continue;
                }
                Update update = new Update();
                set(update, "citedByCountScopus", scopus);
                set(update, "citedByCountOpenAlex", openAlex);
                bulk.updateOne(Query.query(Criteria.where("_id").is(fact.getId())), update);
                pending++;
            }
            if (pending > 0) {
                bulk.execute();
                updated += pending;
            }
            if (scanned % 20_000 == 0) {
                log.info("Citation-source backfill: scanned={} updated={}", scanned, updated);
            }
        }
        Result result = new Result(scanned, scopusSet, openAlexSet, updated);
        log.info("Citation-source backfill done in {} ms: {}", (System.nanoTime() - started) / 1_000_000, result);
        return result;
    }

    private static void set(Update update, String field, Integer value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    /** Scopus counts keyed by canonical id; a canonical pub with several Scopus records keeps the highest. */
    private Map<String, Integer> scopusCounts(List<String> canonicalIds) {
        List<ScholardexSourceLink> links = sourceLinkRepository.findByEntityTypeAndSourceAndCanonicalEntityIdIn(
                ScholardexEntityType.PUBLICATION, SOURCE_SCOPUS, canonicalIds);
        Map<String, List<String>> canonicalBySourceRecord = groupByRecord(links);
        Map<String, Integer> counts = new HashMap<>();
        for (ScopusPublicationFact source : scopusPublicationFactRepository.findByEidIn(canonicalBySourceRecord.keySet())) {
            merge(counts, canonicalBySourceRecord.get(source.getEid()), source.getCitedByCount());
        }
        return counts;
    }

    private Map<String, Integer> openAlexCounts(List<String> canonicalIds) {
        List<ScholardexSourceLink> links = sourceLinkRepository.findByEntityTypeAndSourceAndCanonicalEntityIdIn(
                ScholardexEntityType.PUBLICATION, SOURCE_OPENALEX, canonicalIds);
        Map<String, List<String>> canonicalBySourceRecord = groupByRecord(links);
        Map<String, Integer> counts = new HashMap<>();
        for (OpenAlexPublicationFact source
                : openAlexPublicationFactRepository.findBySourceRecordIdIn(canonicalBySourceRecord.keySet())) {
            merge(counts, canonicalBySourceRecord.get(source.getSourceRecordId()), source.getCitedByCount());
        }
        return counts;
    }

    private static Map<String, List<String>> groupByRecord(List<ScholardexSourceLink> links) {
        Map<String, List<String>> byRecord = new HashMap<>();
        for (ScholardexSourceLink link : links) {
            if (link.getSourceRecordId() == null || link.getCanonicalEntityId() == null) {
                continue;
            }
            byRecord.computeIfAbsent(link.getSourceRecordId(), k -> new ArrayList<>()).add(link.getCanonicalEntityId());
        }
        return byRecord;
    }

    private static void merge(Map<String, Integer> counts, List<String> canonicalIds, Integer count) {
        if (count == null || canonicalIds == null) {
            return;
        }
        for (String canonicalId : canonicalIds) {
            counts.merge(canonicalId, count, Math::max);
        }
    }
}
