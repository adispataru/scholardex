package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusFundingFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusFundingFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ScopusFactBuilderService {

    private static final Logger log = LoggerFactory.getLogger(ScopusFactBuilderService.class);
    private static final int FACT_BUILD_CHUNK_SIZE = 1_000;
    private static final int FACT_BUILD_HEARTBEAT_INTERVAL = 10_000;

    private final ScopusImportEventRepository importEventRepository;
    private final ScopusPublicationFactRepository publicationFactRepository;
    private final ScopusCitationFactRepository citationFactRepository;
    private final ScopusForumFactRepository forumFactRepository;
    private final ScopusAuthorFactRepository authorFactRepository;
    private final ScopusAffiliationFactRepository affiliationFactRepository;
    private final ScopusFundingFactRepository fundingFactRepository;
    private final ObjectMapper objectMapper;

    public ImportProcessingResult buildFactsFromImportEvents() {
        return buildFactsFromImportEvents(null);
    }

    public ImportProcessingResult buildFactsFromImportEvents(String batchId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScopusImportEvent> events = isBlank(batchId)
                ? new ArrayList<>(importEventRepository.findAll())
                : new ArrayList<>(importEventRepository.findByBatchId(batchId));
        events.sort(Comparator
                .comparing(ScopusImportEvent::getEntityType, Comparator.nullsLast(Enum::compareTo))
                .thenComparing(ScopusImportEvent::getSource, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusImportEvent::getSourceRecordId, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusImportEvent::getPayloadHash, Comparator.nullsLast(String::compareTo)));

        List<PublicationWorkItem> publicationEvents = new ArrayList<>();
        List<CitationWorkItem> citationEvents = new ArrayList<>();

        for (ScopusImportEvent event : events) {
            result.markProcessed();
            maybeLogProgress(result);
            if (event == null || event.getEntityType() == null || event.getPayload() == null) {
                result.markSkipped(sample(event, "missing event metadata"));
                continue;
            }
            try {
                JsonNode payload = objectMapper.readTree(event.getPayload());
                if (event.getEntityType() == ScopusImportEntityType.PUBLICATION) {
                    publicationEvents.add(new PublicationWorkItem(event, payload));
                    continue;
                }
                if (event.getEntityType() == ScopusImportEntityType.CITATION) {
                    citationEvents.add(new CitationWorkItem(event, payload));
                    continue;
                }
                result.markSkipped(sample(event, "entity type not supported in H17.4: " + event.getEntityType()));
            } catch (Exception e) {
                result.markError(sample(event, e.getMessage()));
                log.error("Scopus fact-builder event failed: entityType={}, source={}, sourceRecordId={}, reason={}",
                        event.getEntityType(), event.getSource(), event.getSourceRecordId(), e.getMessage(), e);
            }
        }

        log.info("Scopus fact-builder start: scope={}, totalEvents={}, publications={}, citations={}, chunkSize={}",
                isBlank(batchId) ? "all-events" : "batch=" + batchId,
                events.size(),
                publicationEvents.size(),
                citationEvents.size(),
                FACT_BUILD_CHUNK_SIZE);

        processPublicationChunks(publicationEvents, result);
        processCitationChunks(citationEvents, result);

        log.info("Scopus fact-builder summary: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                result.getSkippedCount(), result.getErrorCount(), result.getErrorsSample());
        return result;
    }

    private void processPublicationChunks(List<PublicationWorkItem> publicationEvents, ImportProcessingResult result) {
        int total = publicationEvents.size();
        int totalBatches = total == 0 ? 0 : ((total - 1) / FACT_BUILD_CHUNK_SIZE) + 1;

        int chunkNo = 0;
        for (int from = 0; from < total; from += FACT_BUILD_CHUNK_SIZE) {
            chunkNo++;
            int to = Math.min(total, from + FACT_BUILD_CHUNK_SIZE);
            int batchIndex = from / FACT_BUILD_CHUNK_SIZE;
            int importedBefore = result.getImportedCount();
            int updatedBefore = result.getUpdatedCount();
            int skippedBefore = result.getSkippedCount();
            int errorsBefore = result.getErrorCount();

            ChunkTimings timings = upsertPublicationItems(publicationEvents.subList(from, to), result);

            log.info("Scopus fact-builder publication chunk {} complete [batch={} / totalBatches={}]: events={} imported={} updated={} skipped={} errors={} timingsMs[preload={}, process={}, save={}, total={}]",
                    chunkNo,
                    batchIndex,
                    totalBatches,
                    to - from,
                    result.getImportedCount() - importedBefore,
                    result.getUpdatedCount() - updatedBefore,
                    result.getSkippedCount() - skippedBefore,
                    result.getErrorCount() - errorsBefore,
                    timings.preloadMs,
                    timings.processMs,
                    timings.saveMs,
                    timings.totalMs);
        }
    }

    private void processCitationChunks(List<CitationWorkItem> citationEvents, ImportProcessingResult result) {
        int total = citationEvents.size();
        int totalBatches = total == 0 ? 0 : ((total - 1) / FACT_BUILD_CHUNK_SIZE) + 1;

        int chunkNo = 0;
        for (int from = 0; from < total; from += FACT_BUILD_CHUNK_SIZE) {
            chunkNo++;
            int to = Math.min(total, from + FACT_BUILD_CHUNK_SIZE);
            int batchIndex = from / FACT_BUILD_CHUNK_SIZE;
            int importedBefore = result.getImportedCount();
            int updatedBefore = result.getUpdatedCount();
            int skippedBefore = result.getSkippedCount();
            int errorsBefore = result.getErrorCount();

            ChunkTimings timings = upsertCitationItems(citationEvents.subList(from, to), result);

            log.info("Scopus fact-builder citation chunk {} complete [batch={} / totalBatches={}]: events={} imported={} updated={} skipped={} errors={} timingsMs[preload={}, process={}, save={}, total={}]",
                    chunkNo,
                    batchIndex,
                    totalBatches,
                    to - from,
                    result.getImportedCount() - importedBefore,
                    result.getUpdatedCount() - updatedBefore,
                    result.getSkippedCount() - skippedBefore,
                    result.getErrorCount() - errorsBefore,
                    timings.preloadMs,
                    timings.processMs,
                    timings.saveMs,
                    timings.totalMs);
        }
    }

    private ChunkTimings upsertPublicationItems(List<PublicationWorkItem> items, ImportProcessingResult result) {
        long startedAtNanos = System.nanoTime();
        PublicationChunkState state = preloadPublicationChunkState(items);
        long preloadFinishedAtNanos = System.nanoTime();

        for (PublicationWorkItem item : items) {
            upsertPublicationAndDimensions(item.event, item.payload, result, state);
        }
        long processFinishedAtNanos = System.nanoTime();

        flushPublicationChunkState(state);
        long saveFinishedAtNanos = System.nanoTime();

        return new ChunkTimings(
                nanosToMillis(preloadFinishedAtNanos - startedAtNanos),
                nanosToMillis(processFinishedAtNanos - preloadFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - processFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - startedAtNanos)
        );
    }

    private ChunkTimings upsertCitationItems(List<CitationWorkItem> items, ImportProcessingResult result) {
        long startedAtNanos = System.nanoTime();

        Set<String> citedEids = new LinkedHashSet<>();
        Set<String> citingEids = new LinkedHashSet<>();
        for (CitationWorkItem item : items) {
            String citedEid = text(item.payload, "citedEid");
            String citingEid = text(item.payload, "citingEid");
            if (isBlank(citedEid) || isBlank(citingEid)) {
                String sourceRecordId = item.event.getSourceRecordId();
                if (!isBlank(sourceRecordId) && sourceRecordId.contains("->")) {
                    String[] parts = sourceRecordId.split("->", 2);
                    citedEid = isBlank(citedEid) ? parts[0] : citedEid;
                    citingEid = isBlank(citingEid) ? parts[1] : citingEid;
                }
            }
            if (!isBlank(citedEid) && !isBlank(citingEid)) {
                citedEids.add(citedEid);
                citingEids.add(citingEid);
            }
        }

        Map<CitationEdgeKey, ScopusCitationFact> citationByEdge = new LinkedHashMap<>();
        if (!citedEids.isEmpty() && !citingEids.isEmpty()) {
            List<ScopusCitationFact> existing = citationFactRepository.findByCitedEidInAndCitingEidIn(citedEids, citingEids);
            for (ScopusCitationFact fact : existing) {
                citationByEdge.put(new CitationEdgeKey(fact.getCitedEid(), fact.getCitingEid()), fact);
            }
        }
        Map<CitationEdgeKey, ScopusCitationFact> pendingCitationSaves = new LinkedHashMap<>();

        List<PublicationWorkItem> citationBackfillCandidates = new ArrayList<>();
        Set<String> candidateCitingEids = new LinkedHashSet<>();

        long preloadFinishedAtNanos = System.nanoTime();

        for (CitationWorkItem item : items) {
            upsertCitation(item.event, item.payload, result, citationByEdge, pendingCitationSaves);

            JsonNode citingItem = item.payload.path("citingItem");
            if (!citingItem.isMissingNode() && !citingItem.isNull()) {
                String citingEid = text(citingItem, "eid");
                if (!isBlank(citingEid)) {
                    citationBackfillCandidates.add(new PublicationWorkItem(item.event, citingItem));
                    candidateCitingEids.add(citingEid);
                }
            }
        }

        List<PublicationWorkItem> citationBackfills = new ArrayList<>();
        if (!candidateCitingEids.isEmpty()) {
            Set<String> existingCitingEids = new LinkedHashSet<>();
            for (ScopusPublicationFact fact : publicationFactRepository.findByEidIn(candidateCitingEids)) {
                existingCitingEids.add(fact.getEid());
            }
            for (PublicationWorkItem candidate : citationBackfillCandidates) {
                String citingEid = text(candidate.payload, "eid");
                if (!isBlank(citingEid) && !existingCitingEids.contains(citingEid)) {
                    citationBackfills.add(candidate);
                }
            }
        }

        long citationProcessFinishedAtNanos = System.nanoTime();

        if (!pendingCitationSaves.isEmpty()) {
            citationFactRepository.saveAll(pendingCitationSaves.values());
        }
        long citationSaveFinishedAtNanos = System.nanoTime();

        ChunkTimings backfillTimings = citationBackfills.isEmpty()
                ? new ChunkTimings(0L, 0L, 0L, 0L)
                : upsertPublicationItems(citationBackfills, result);

        long finishedAtNanos = System.nanoTime();

        return new ChunkTimings(
                nanosToMillis(preloadFinishedAtNanos - startedAtNanos) + backfillTimings.preloadMs,
                nanosToMillis(citationProcessFinishedAtNanos - preloadFinishedAtNanos) + backfillTimings.processMs,
                nanosToMillis(citationSaveFinishedAtNanos - citationProcessFinishedAtNanos) + backfillTimings.saveMs,
                nanosToMillis(finishedAtNanos - startedAtNanos)
        );
    }

    private PublicationChunkState preloadPublicationChunkState(List<PublicationWorkItem> items) {
        Set<String> eids = new LinkedHashSet<>();
        Set<String> sourceIds = new LinkedHashSet<>();
        Set<String> authorIds = new LinkedHashSet<>();
        Set<String> afids = new LinkedHashSet<>();
        Set<String> fundingKeys = new LinkedHashSet<>();

        for (PublicationWorkItem item : items) {
            JsonNode payload = item.payload;
            String eid = text(payload, "eid");
            if (!isBlank(eid)) {
                eids.add(eid);
            }
            String sourceId = text(payload, "source_id");
            if (!isBlank(sourceId)) {
                sourceIds.add(sourceId);
            }
            authorIds.addAll(distinctNonBlank(splitSemicolon(text(payload, "author_ids"))));
            afids.addAll(distinctNonBlank(splitSemicolon(text(payload, "afid"))));
            String acronym = text(payload, "fund_acr");
            if (!isBlank(acronym)) {
                fundingKeys.add(normalizeFundingKey(acronym, text(payload, "fund_no"), text(payload, "fund_sponsor")));
            }
        }

        return new PublicationChunkState(
                mapByKey(publicationFactRepository.findByEidIn(eids), ScopusPublicationFact::getEid),
                mapByKey(forumFactRepository.findBySourceIdIn(sourceIds), ScopusForumFact::getSourceId),
                mapByKey(authorFactRepository.findByAuthorIdIn(authorIds), ScopusAuthorFact::getAuthorId),
                mapByKey(affiliationFactRepository.findByAfidIn(afids), ScopusAffiliationFact::getAfid),
                mapByKey(fundingFactRepository.findByFundingKeyIn(fundingKeys), ScopusFundingFact::getFundingKey)
        );
    }

    private void flushPublicationChunkState(PublicationChunkState state) {
        if (!state.pendingPublicationSaves.isEmpty()) {
            publicationFactRepository.saveAll(state.pendingPublicationSaves.values());
        }
        if (!state.pendingForumSaves.isEmpty()) {
            forumFactRepository.saveAll(state.pendingForumSaves.values());
        }
        if (!state.pendingAuthorSaves.isEmpty()) {
            authorFactRepository.saveAll(state.pendingAuthorSaves.values());
        }
        if (!state.pendingAffiliationSaves.isEmpty()) {
            affiliationFactRepository.saveAll(state.pendingAffiliationSaves.values());
        }
        if (!state.pendingFundingSaves.isEmpty()) {
            fundingFactRepository.saveAll(state.pendingFundingSaves.values());
        }
    }

    private void upsertPublicationAndDimensions(
            ScopusImportEvent event,
            JsonNode payload,
            ImportProcessingResult result,
            PublicationChunkState state
    ) {
        String eid = text(payload, "eid");
        if (isBlank(eid)) {
            result.markSkipped(sample(event, "publication payload missing eid"));
            return;
        }

        ScopusPublicationFact fact = state.publicationByEid.get(eid);
        boolean created = fact == null;
        if (created) {
            fact = new ScopusPublicationFact();
            state.publicationByEid.put(eid, fact);
        } else if (samePayloadHash(fact.getLastPayloadHash(), event.getPayloadHash())) {
            result.markSkipped(sample(event, "publication payload unchanged"));
            return;
        }

        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        String subtype = text(payload, "subtype");
        String subtypeDescription = text(payload, "subtypeDescription");
        String fundingKey = normalizeFundingKey(text(payload, "fund_acr"), text(payload, "fund_no"), text(payload, "fund_sponsor"));
        fact.setDoi(text(payload, "doi"));
        fact.setEid(eid);
        fact.setTitle(text(payload, "title"));
        fact.setSubtype(subtype);
        fact.setSubtypeDescription(subtypeDescription);
        fact.setScopusSubtype(subtype);
        fact.setScopusSubtypeDescription(subtypeDescription);
        fact.setCreator(text(payload, "creator"));
        fact.setAuthorCount(intValue(payload, "author_count"));
        fact.setAuthors(distinctNonBlank(splitSemicolon(text(payload, "author_ids"))));
        fact.setAuthorAffiliationSourceIds(splitSemicolon(text(payload, "author_afids")));
        fact.setCorrespondingAuthors(distinctNonBlank(splitSemicolon(text(payload, "correspondingAuthors"))));
        fact.setAffiliations(distinctNonBlank(splitSemicolon(text(payload, "afid"))));
        fact.setForumId(text(payload, "source_id"));
        fact.setVolume(text(payload, "volume"));
        fact.setIssueIdentifier(text(payload, "issueIdentifier"));
        fact.setCoverDate(text(payload, "coverDate"));
        fact.setCoverDisplayDate(text(payload, "coverDisplayDate"));
        fact.setDescription(text(payload, "description"));
        fact.setCitedByCount(intValue(payload, "citedby_count"));
        fact.setOpenAccess(boolValue(payload, "openaccess"));
        fact.setFreetoread(text(payload, "freetoread"));
        fact.setFreetoreadLabel(text(payload, "freetoreadLabel"));
        fact.setFundingId(isBlank(fundingKey) || "||".equals(fundingKey) ? "" : fundingKey);
        fact.setArticleNumber(text(payload, "article_number"));
        fact.setPageRange(text(payload, "pageRange"));
        fact.setApproved(boolValue(payload, "approved"));
        applyLineage(fact, event);
        fact.setLastPayloadHash(event.getPayloadHash());
        fact.setLastMaterializedAt(now);
        fact.setUpdatedAt(now);
        state.pendingPublicationSaves.put(eid, fact);
        markImportOrUpdate(result, created);

        upsertForumFact(event, payload, result, state);
        upsertAuthorFacts(event, payload, result, state);
        upsertAffiliationFacts(event, payload, result, state);
        upsertFundingFact(event, payload, result, state);
    }

    private void upsertCitation(
            ScopusImportEvent event,
            JsonNode payload,
            ImportProcessingResult result,
            Map<CitationEdgeKey, ScopusCitationFact> citationByEdge,
            Map<CitationEdgeKey, ScopusCitationFact> pendingCitationSaves
    ) {
        String citedEid = text(payload, "citedEid");
        String citingEid = text(payload, "citingEid");
        if (isBlank(citedEid) || isBlank(citingEid)) {
            String sourceRecordId = event.getSourceRecordId();
            if (!isBlank(sourceRecordId) && sourceRecordId.contains("->")) {
                String[] parts = sourceRecordId.split("->", 2);
                citedEid = isBlank(citedEid) ? parts[0] : citedEid;
                citingEid = isBlank(citingEid) ? parts[1] : citingEid;
            }
        }
        if (isBlank(citedEid) || isBlank(citingEid)) {
            result.markSkipped(sample(event, "citation payload missing edge eids"));
            return;
        }

        CitationEdgeKey edgeKey = new CitationEdgeKey(citedEid, citingEid);
        ScopusCitationFact fact = citationByEdge.get(edgeKey);
        boolean created = fact == null;
        if (created) {
            fact = new ScopusCitationFact();
            citationByEdge.put(edgeKey, fact);
        } else if (samePayloadHash(fact.getLastPayloadHash(), event.getPayloadHash())) {
            result.markSkipped(sample(event, "citation payload unchanged"));
            return;
        }

        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setCitedEid(citedEid);
        fact.setCitingEid(citingEid);
        applyLineage(fact, event);
        fact.setLastPayloadHash(event.getPayloadHash());
        fact.setLastMaterializedAt(now);
        fact.setUpdatedAt(now);
        pendingCitationSaves.put(edgeKey, fact);
        markImportOrUpdate(result, created);
    }

    private void upsertForumFact(
            ScopusImportEvent event,
            JsonNode payload,
            ImportProcessingResult result,
            PublicationChunkState state
    ) {
        String sourceId = text(payload, "source_id");
        if (isBlank(sourceId)) {
            return;
        }

        ScopusForumFact fact = state.forumBySourceId.get(sourceId);
        boolean created = fact == null;
        if (created) {
            fact = new ScopusForumFact();
            state.forumBySourceId.put(sourceId, fact);
        }
        String payloadHash = hashKey("forum",
                sourceId,
                text(payload, "publicationName"),
                normalizeIssn(text(payload, "issn")),
                normalizeIssn(text(payload, "eIssn")),
                text(payload, "aggregationType"));
        if (!created && samePayloadHash(fact.getLastPayloadHash(), payloadHash)) {
            return;
        }

        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setSourceId(sourceId);
        fact.setPublicationName(text(payload, "publicationName"));
        fact.setIssn(normalizeIssn(text(payload, "issn")));
        fact.setEIssn(normalizeIssn(text(payload, "eIssn")));
        fact.setAggregationType(text(payload, "aggregationType"));
        applyLineage(fact, event);
        fact.setLastPayloadHash(payloadHash);
        fact.setLastMaterializedAt(now);
        fact.setUpdatedAt(now);
        state.pendingForumSaves.put(sourceId, fact);
        markImportOrUpdate(result, created);
    }

    private void upsertAuthorFacts(
            ScopusImportEvent event,
            JsonNode payload,
            ImportProcessingResult result,
            PublicationChunkState state
    ) {
        List<String> authorIds = splitSemicolon(text(payload, "author_ids"));
        List<String> authorNames = splitSemicolon(text(payload, "author_names"));
        List<String> authorAfids = splitSemicolon(text(payload, "author_afids"));
        int n = Math.min(authorIds.size(), Math.min(authorNames.size(), authorAfids.size()));

        for (int i = 0; i < n; i++) {
            String authorId = trim(authorIds.get(i));
            if (isBlank(authorId)) {
                continue;
            }

            ScopusAuthorFact fact = state.authorById.get(authorId);
            boolean created = fact == null;
            if (created) {
                fact = new ScopusAuthorFact();
                state.authorById.put(authorId, fact);
            }
            String payloadHash = hashKey("author",
                    authorId,
                    trim(authorNames.get(i)),
                    String.join(",", distinctNonBlank(splitDash(authorAfids.get(i)))));
            if (!created && samePayloadHash(fact.getLastPayloadHash(), payloadHash)) {
                continue;
            }

            Instant now = Instant.now();
            if (fact.getCreatedAt() == null) {
                fact.setCreatedAt(now);
            }
            fact.setAuthorId(authorId);
            fact.setName(trim(authorNames.get(i)));
            fact.setAffiliationIds(distinctNonBlank(splitDash(authorAfids.get(i))));
            applyLineage(fact, event);
            fact.setLastPayloadHash(payloadHash);
            fact.setLastMaterializedAt(now);
            fact.setUpdatedAt(now);
            state.pendingAuthorSaves.put(authorId, fact);
            markImportOrUpdate(result, created);
        }
    }

    private void upsertAffiliationFacts(
            ScopusImportEvent event,
            JsonNode payload,
            ImportProcessingResult result,
            PublicationChunkState state
    ) {
        List<String> afids = splitSemicolon(text(payload, "afid"));
        List<String> names = splitSemicolon(text(payload, "affilname"));
        List<String> cities = splitSemicolon(text(payload, "affiliation_city"));
        List<String> countries = splitSemicolon(text(payload, "affiliation_country"));

        for (int i = 0; i < afids.size(); i++) {
            String afid = trim(afids.get(i));
            if (isBlank(afid)) {
                continue;
            }

            ScopusAffiliationFact fact = state.affiliationById.get(afid);
            boolean created = fact == null;
            if (created) {
                fact = new ScopusAffiliationFact();
                state.affiliationById.put(afid, fact);
            }
            String payloadHash = hashKey("affiliation",
                    afid,
                    arrayValue(names, i),
                    arrayValue(cities, i),
                    arrayValue(countries, i));
            if (!created && samePayloadHash(fact.getLastPayloadHash(), payloadHash)) {
                continue;
            }

            Instant now = Instant.now();
            if (fact.getCreatedAt() == null) {
                fact.setCreatedAt(now);
            }
            fact.setAfid(afid);
            fact.setName(arrayValue(names, i));
            fact.setCity(arrayValue(cities, i));
            fact.setCountry(arrayValue(countries, i));
            applyLineage(fact, event);
            fact.setLastPayloadHash(payloadHash);
            fact.setLastMaterializedAt(now);
            fact.setUpdatedAt(now);
            state.pendingAffiliationSaves.put(afid, fact);
            markImportOrUpdate(result, created);
        }
    }

    private void upsertFundingFact(
            ScopusImportEvent event,
            JsonNode payload,
            ImportProcessingResult result,
            PublicationChunkState state
    ) {
        String acronym = text(payload, "fund_acr");
        if (isBlank(acronym)) {
            return;
        }
        String fundingKey = normalizeFundingKey(acronym, text(payload, "fund_no"), text(payload, "fund_sponsor"));

        ScopusFundingFact fact = state.fundingByKey.get(fundingKey);
        boolean created = fact == null;
        if (created) {
            fact = new ScopusFundingFact();
            state.fundingByKey.put(fundingKey, fact);
        }
        String payloadHash = hashKey("funding", acronym, text(payload, "fund_no"), text(payload, "fund_sponsor"));
        if (!created && samePayloadHash(fact.getLastPayloadHash(), payloadHash)) {
            return;
        }

        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setAcronym(acronym);
        fact.setNumber(text(payload, "fund_no"));
        fact.setSponsor(text(payload, "fund_sponsor"));
        fact.setFundingKey(fundingKey);
        applyLineage(fact, event);
        fact.setLastPayloadHash(payloadHash);
        fact.setLastMaterializedAt(now);
        fact.setUpdatedAt(now);
        state.pendingFundingSaves.put(fundingKey, fact);
        markImportOrUpdate(result, created);
    }

    private void maybeLogProgress(ImportProcessingResult result) {
        if (result.getProcessedCount() % FACT_BUILD_HEARTBEAT_INTERVAL == 0) {
            log.info("Scopus fact-builder progress: processed={} imported={} updated={} skipped={} errors={}",
                    result.getProcessedCount(),
                    result.getImportedCount(),
                    result.getUpdatedCount(),
                    result.getSkippedCount(),
                    result.getErrorCount());
        }
    }

    private void markImportOrUpdate(ImportProcessingResult result, boolean created) {
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    private void applyLineage(ScopusPublicationFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusCitationFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusForumFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusAuthorFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusAffiliationFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusFundingFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private String sample(ScopusImportEvent event, String message) {
        if (event == null) {
            return "null-event " + message;
        }
        return event.getEntityType() + ":" + event.getSource() + ":" + event.getSourceRecordId() + " " + message;
    }

    private <T> Map<String, T> mapByKey(Collection<T> values, java.util.function.Function<T, String> keyExtractor) {
        Map<String, T> out = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) {
            return out;
        }
        for (T value : values) {
            String key = keyExtractor.apply(value);
            if (!isBlank(key)) {
                out.put(key, value);
            }
        }
        return out;
    }

    private List<String> splitSemicolon(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        String[] raw = value.split(";");
        List<String> out = new ArrayList<>(raw.length);
        for (String part : raw) {
            out.add(trim(part));
        }
        return out;
    }

    private List<String> splitDash(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        String[] raw = value.split("-");
        List<String> out = new ArrayList<>(raw.length);
        for (String part : raw) {
            out.add(trim(part));
        }
        return out;
    }

    private List<String> distinctNonBlank(List<String> rawValues) {
        Set<String> out = new LinkedHashSet<>();
        if (rawValues == null) {
            return List.of();
        }
        for (String value : rawValues) {
            String normalized = trim(value);
            if (!isBlank(normalized)) {
                out.add(normalized);
            }
        }
        return new ArrayList<>(out);
    }

    private String arrayValue(List<String> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return "";
        }
        return trim(values.get(index));
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return "";
        }
        return trim(v.asText(""));
    }

    private Integer intValue(JsonNode node, String field) {
        if (node == null || field == null) {
            return 0;
        }
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return 0;
        }
        if (v.canConvertToInt()) {
            return v.asInt();
        }
        try {
            return Integer.parseInt(trim(v.asText("0")));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Boolean boolValue(JsonNode node, String field) {
        if (node == null || field == null) {
            return Boolean.FALSE;
        }
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return Boolean.FALSE;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            return v.asInt() != 0;
        }
        String raw = trim(v.asText("false")).toLowerCase(Locale.ROOT);
        return "1".equals(raw) || "true".equals(raw) || "yes".equals(raw);
    }

    private String normalizeIssn(String value) {
        String normalized = trim(value);
        if (isBlank(normalized)) {
            return "";
        }
        if (!normalized.contains("-") && normalized.length() == 8) {
            return normalized.substring(0, 4) + "-" + normalized.substring(4);
        }
        return normalized;
    }

    private String normalizeFundingKey(String acronym, String number, String sponsor) {
        return (trim(acronym) + "|" + trim(number) + "|" + trim(sponsor)).toLowerCase(Locale.ROOT);
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean samePayloadHash(String previous, String current) {
        return !isBlank(previous) && !isBlank(current) && previous.equals(current);
    }

    private String hashKey(String... values) {
        StringBuilder material = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (material.length() > 0) {
                    material.append('|');
                }
                material.append(trim(value));
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(material.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record PublicationWorkItem(ScopusImportEvent event, JsonNode payload) {
    }

    private record CitationWorkItem(ScopusImportEvent event, JsonNode payload) {
    }

    private record CitationEdgeKey(String citedEid, String citingEid) {
    }

    private static final class PublicationChunkState {
        private final Map<String, ScopusPublicationFact> publicationByEid;
        private final Map<String, ScopusForumFact> forumBySourceId;
        private final Map<String, ScopusAuthorFact> authorById;
        private final Map<String, ScopusAffiliationFact> affiliationById;
        private final Map<String, ScopusFundingFact> fundingByKey;

        private final Map<String, ScopusPublicationFact> pendingPublicationSaves = new LinkedHashMap<>();
        private final Map<String, ScopusForumFact> pendingForumSaves = new LinkedHashMap<>();
        private final Map<String, ScopusAuthorFact> pendingAuthorSaves = new LinkedHashMap<>();
        private final Map<String, ScopusAffiliationFact> pendingAffiliationSaves = new LinkedHashMap<>();
        private final Map<String, ScopusFundingFact> pendingFundingSaves = new LinkedHashMap<>();

        private PublicationChunkState(
                Map<String, ScopusPublicationFact> publicationByEid,
                Map<String, ScopusForumFact> forumBySourceId,
                Map<String, ScopusAuthorFact> authorById,
                Map<String, ScopusAffiliationFact> affiliationById,
                Map<String, ScopusFundingFact> fundingByKey
        ) {
            this.publicationByEid = publicationByEid;
            this.forumBySourceId = forumBySourceId;
            this.authorById = authorById;
            this.affiliationById = affiliationById;
            this.fundingByKey = fundingByKey;
        }
    }

    private static final class ChunkTimings {
        private final long preloadMs;
        private final long processMs;
        private final long saveMs;
        private final long totalMs;

        private ChunkTimings(long preloadMs, long processMs, long saveMs, long totalMs) {
            this.preloadMs = preloadMs;
            this.processMs = processMs;
            this.saveMs = saveMs;
            this.totalMs = totalMs;
        }
    }
}
