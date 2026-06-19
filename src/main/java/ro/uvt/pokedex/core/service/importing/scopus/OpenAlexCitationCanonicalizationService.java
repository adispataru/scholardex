package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexWorkDoi;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexWorkDoiRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.openalex.OpenAlexClient;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash;

/**
 * H66B Phase 4a Stage 2 — DOI-keyed OpenAlex citation edges. A synced work's {@code referenced_works} are bare
 * OpenAlex ids; this builder resolves their DOIs (fetch + durable {@code openalex.work_doi} cache), matches each DOI
 * against the canonical publication corpus, and writes a {@code ScholardexCitationFact} (citing = the synced work's
 * canonical pub, cited = the referenced pub) for every edge whose <b>both</b> endpoints are canonical. A new
 * DOI-keyed path beside the EID-keyed Scopus citation builder; edges to papers outside the corpus are skipped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAlexCitationCanonicalizationService {

    private static final String SOURCE_OPENALEX = "OPENALEX";

    private final OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    private final OpenAlexWorkDoiRepository workDoiRepository;
    private final OpenAlexClient openAlexClient;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexCitationFactRepository citationFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;

    /** Full-rebuild replay: build DOI-keyed citation edges for every OpenAlex source-fact. */
    public ImportProcessingResult rebuildCitationFacts() {
        return build(new ArrayList<>(openAlexPublicationFactRepository.findAll()));
    }

    /** On-demand: build citation edges for just the works touched by a sync batch. */
    public ImportProcessingResult resolve(Collection<String> sourceRecordIds) {
        if (sourceRecordIds == null || sourceRecordIds.isEmpty()) {
            return new ImportProcessingResult(20);
        }
        return build(new ArrayList<>(openAlexPublicationFactRepository.findBySourceRecordIdIn(sourceRecordIds)));
    }

    private ImportProcessingResult build(List<OpenAlexPublicationFact> citingWorks) {
        ImportProcessingResult result = new ImportProcessingResult(20);

        // 1) Collect referenced work ids and ensure their DOIs are cached (fetch the missing ones).
        Set<String> referencedIds = new LinkedHashSet<>();
        for (OpenAlexPublicationFact work : citingWorks) {
            if (work.getReferencedWorks() != null) {
                work.getReferencedWorks().stream().filter(id -> id != null && !id.isBlank()).forEach(referencedIds::add);
            }
        }
        Map<String, String> doiByWorkId = ensureReferencedDoisCached(referencedIds);

        // 2) For each citing work, resolve its own canonical pub, then each referenced work by DOI; write the edge
        //    when both endpoints are canonical (and distinct).
        for (OpenAlexPublicationFact work : citingWorks) {
            result.markProcessed();
            String citingCanonicalId = resolveCanonicalByOpenAlexWorkId(work.getSourceRecordId());
            if (citingCanonicalId == null || work.getReferencedWorks() == null) {
                continue;
            }
            for (String referencedId : work.getReferencedWorks()) {
                String referencedDoi = ScholardexPublicationCanonicalizationService.normalizeDoi(doiByWorkId.get(referencedId));
                if (referencedDoi == null) {
                    continue; // referenced work has no DOI (or we couldn't fetch it)
                }
                List<ScholardexPublicationFact> cited = scholardexPublicationFactRepository.findAllByDoiNormalized(referencedDoi);
                if (cited.size() != 1) {
                    continue; // not in our corpus, or shared/container DOI (ambiguous) — skip
                }
                String citedCanonicalId = cited.getFirst().getId();
                if (citedCanonicalId == null || citedCanonicalId.equals(citingCanonicalId)) {
                    continue; // no self-citation edge
                }
                writeCitation(citedCanonicalId, citingCanonicalId, work, result);
            }
        }
        log.info("OpenAlex citation build: citingWorks={} referencedIds={} doisCached={} edgesWritten={} skipped={}",
                citingWorks.size(), referencedIds.size(), doiByWorkId.size(), result.getImportedCount(), result.getSkippedCount());
        return result;
    }

    /** Load cached DOIs for the referenced ids; fetch + persist the missing ones (caching null for no-DOI works). */
    private Map<String, String> ensureReferencedDoisCached(Set<String> referencedIds) {
        Map<String, String> doiByWorkId = new HashMap<>();
        if (referencedIds.isEmpty()) {
            return doiByWorkId;
        }
        Set<String> missing = new LinkedHashSet<>(referencedIds);
        for (OpenAlexWorkDoi cached : workDoiRepository.findByIdIn(referencedIds)) {
            doiByWorkId.put(cached.getId(), cached.getDoi());
            missing.remove(cached.getId());
        }
        if (!missing.isEmpty()) {
            Instant now = Instant.now();
            Set<String> returned = new LinkedHashSet<>();
            for (OpenAlexWorksResponse.OpenAlexWork work : openAlexClient.fetchWorksByIds(missing)) {
                String workId = stripPrefix(work.getId());
                if (workId == null) {
                    continue;
                }
                returned.add(workId);
                persistCache(workId, work.getDoi(), now);
                doiByWorkId.put(workId, work.getDoi());
            }
            // Ids OpenAlex didn't return (deleted/merged) — cache null so we don't re-fetch them.
            for (String id : missing) {
                if (!returned.contains(id)) {
                    persistCache(id, null, now);
                    doiByWorkId.put(id, null);
                }
            }
        }
        return doiByWorkId;
    }

    private void persistCache(String workId, String doi, Instant now) {
        OpenAlexWorkDoi entry = workDoiRepository.findById(workId).orElseGet(OpenAlexWorkDoi::new);
        entry.setId(workId);
        entry.setDoi(doi);
        entry.setFetchedAt(now);
        workDoiRepository.save(entry);
    }

    private String resolveCanonicalByOpenAlexWorkId(String workId) {
        if (workId == null || workId.isBlank()) {
            return null;
        }
        return sourceLinkService.findByKey(ScholardexEntityType.PUBLICATION, SOURCE_OPENALEX, workId)
                .map(link -> link.getCanonicalEntityId())
                .filter(id -> id != null && !id.isBlank())
                .orElse(null);
    }

    private void writeCitation(String citedPublicationId, String citingPublicationId, OpenAlexPublicationFact work, ImportProcessingResult result) {
        String edgeId = "scit_" + shortHash(citedPublicationId + "|" + citingPublicationId);
        if (citationFactRepository.findById(edgeId).isPresent()) {
            result.markSkipped("citation-edge-already-known");
            return;
        }
        Instant now = Instant.now();
        ScholardexCitationFact fact = new ScholardexCitationFact();
        fact.setId(edgeId);
        fact.setCreatedAt(now);
        fact.setCitedPublicationId(citedPublicationId);
        fact.setCitingPublicationId(citingPublicationId);
        fact.setSource(SOURCE_OPENALEX);
        fact.setSourceRecordId(work.getSourceRecordId() + "::cites::" + citedPublicationId);
        fact.setSourceEventId(work.getSourceEventId());
        fact.setSourceBatchId(work.getSourceBatchId());
        fact.setSourceCorrelationId(work.getSourceCorrelationId());
        fact.setUpdatedAt(now);
        citationFactRepository.save(fact);
        result.markImported();
    }

    private static String stripPrefix(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        String prefix = "https://openalex.org/";
        return v.startsWith(prefix) ? v.substring(prefix.length()) : v;
    }
}
