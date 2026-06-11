package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.HasLineageFields;
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
import ro.uvt.pokedex.core.service.importing.BuilderVersion;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.application.UserDefinedWizardOnboardingContract;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
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
    private static final String SOURCE_USER_PUBLICATION_WIZARD = "USER_PUBLICATION_WIZARD";

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
        List<ForumWorkItem> forumEvents = new ArrayList<>();

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
                    if (isUserDefinedSource(event.getSource())) {
                        result.markSkipped(sample(event, "routed to user-defined fact builder"));
                        continue;
                    }
                    publicationEvents.add(new PublicationWorkItem(event, payload));
                    continue;
                }
                if (event.getEntityType() == ScopusImportEntityType.CITATION) {
                    citationEvents.add(new CitationWorkItem(event, payload));
                    continue;
                }
                if (event.getEntityType() == ScopusImportEntityType.FORUM) {
                    forumEvents.add(new ForumWorkItem(event, payload));
                    continue;
                }
                result.markSkipped(sample(event, "entity type not supported in H17.4: " + event.getEntityType()));
            } catch (Exception e) {
                result.markError(sample(event, e.getMessage()));
                log.error("Scopus fact-builder event failed: entityType={}, source={}, sourceRecordId={}, reason={}",
                        event.getEntityType(), event.getSource(), event.getSourceRecordId(), e.getMessage(), e);
            }
        }

        log.info("Scopus fact-builder start: scope={}, totalEvents={}, publications={}, citations={}, forums={}, chunkSize={}",
                isBlank(batchId) ? "all-events" : "batch=" + batchId,
                events.size(),
                publicationEvents.size(),
                citationEvents.size(),
                forumEvents.size(),
                FACT_BUILD_CHUNK_SIZE);

        processPublicationChunks(publicationEvents, result);
        processCitationChunks(citationEvents, result);
        processForumChunks(forumEvents, result);

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

    private void processForumChunks(List<ForumWorkItem> forumEvents, ImportProcessingResult result) {
        int total = forumEvents.size();
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

            ChunkTimings timings = upsertForumItems(forumEvents.subList(from, to), result);

            log.info("Scopus fact-builder forum chunk {} complete [batch={} / totalBatches={}]: events={} imported={} updated={} skipped={} errors={} timingsMs[preload={}, process={}, save={}, total={}]",
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

    private ChunkTimings upsertForumItems(List<ForumWorkItem> items, ImportProcessingResult result) {
        long startedAtNanos = System.nanoTime();

        Set<String> sourceIds = new LinkedHashSet<>();
        for (ForumWorkItem item : items) {
            String sourceId = text(item.payload, "source_id");
            if (!isBlank(sourceId)) {
                sourceIds.add(sourceId);
            }
        }
        PublicationChunkState state = new PublicationChunkState(
                Map.of(),
                mapByKey(forumFactRepository.findBySourceIdIn(sourceIds), ScopusForumFact::getSourceId),
                Map.of(),
                Map.of(),
                Map.of()
        );
        long preloadFinishedAtNanos = System.nanoTime();

        for (ForumWorkItem item : items) {
            upsertForumFact(item.event, item.payload, result, state);
        }
        long processFinishedAtNanos = System.nanoTime();

        if (!state.pendingForumSaves.isEmpty()) {
            state.pendingForumSaves.values().forEach(f -> f.setBuilderVersion(BuilderVersion.SCOPUS_FACT));
            forumFactRepository.saveAll(state.pendingForumSaves.values());
        }
        long saveFinishedAtNanos = System.nanoTime();

        return new ChunkTimings(
                nanosToMillis(preloadFinishedAtNanos - startedAtNanos),
                nanosToMillis(processFinishedAtNanos - preloadFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - processFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - startedAtNanos)
        );
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
            pendingCitationSaves.values().forEach(f -> f.setBuilderVersion(BuilderVersion.SCOPUS_FACT));
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
            state.pendingPublicationSaves.values().forEach(f -> f.setBuilderVersion(BuilderVersion.SCOPUS_FACT));
            publicationFactRepository.saveAll(state.pendingPublicationSaves.values());
        }
        if (!state.pendingForumSaves.isEmpty()) {
            state.pendingForumSaves.values().forEach(f -> f.setBuilderVersion(BuilderVersion.SCOPUS_FACT));
            forumFactRepository.saveAll(state.pendingForumSaves.values());
        }
        if (!state.pendingAuthorSaves.isEmpty()) {
            state.pendingAuthorSaves.values().forEach(f -> f.setBuilderVersion(BuilderVersion.SCOPUS_FACT));
            authorFactRepository.saveAll(state.pendingAuthorSaves.values());
        }
        if (!state.pendingAffiliationSaves.isEmpty()) {
            state.pendingAffiliationSaves.values().forEach(f -> f.setBuilderVersion(BuilderVersion.SCOPUS_FACT));
            affiliationFactRepository.saveAll(state.pendingAffiliationSaves.values());
        }
        if (!state.pendingFundingSaves.isEmpty()) {
            state.pendingFundingSaves.values().forEach(f -> f.setBuilderVersion(BuilderVersion.SCOPUS_FACT));
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
            refreshLineageForReplay(fact, event);
            state.pendingPublicationSaves.put(eid, fact);
            result.markSkipped(sample(event, "publication payload unchanged"));
            upsertForumFact(event, payload, result, state);
            upsertAuthorFacts(event, payload, result, state);
            upsertAffiliationFacts(event, payload, result, state);
            upsertFundingFact(event, payload, result, state);
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
        fact.setPii(text(payload, "pii"));
        fact.setPubmedId(text(payload, "pubmed_id"));
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
        fact.setAuthKeywords(splitAuthKeywords(text(payload, "authkeywords")));
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
            refreshLineageForReplay(fact, event);
            pendingCitationSaves.put(edgeKey, fact);
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
        String incomingIsbnForHash = text(payload, "isbn");
        String effectiveIsbnForHash = (incomingIsbnForHash != null && !incomingIsbnForHash.isBlank())
                ? incomingIsbnForHash
                : fact.getIsbn();
        String incomingPublisherForHash = text(payload, "publisher");
        String effectivePublisherForHash = (incomingPublisherForHash != null && !incomingPublisherForHash.isBlank())
                ? incomingPublisherForHash
                : fact.getPublisher();
        String payloadHash = hashKey("forum",
                sourceId,
                text(payload, "publicationName"),
                normalizeIssn(text(payload, "issn")),
                normalizeIssn(text(payload, "eIssn")),
                effectiveIsbnForHash,
                text(payload, "aggregationType"),
                effectivePublisherForHash);
        if (!created && samePayloadHash(fact.getLastPayloadHash(), payloadHash)) {
            refreshLineageForReplay(fact, event);
            state.pendingForumSaves.put(sourceId, fact);
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
        // For enrichment fields not always supplied by Scopus bulk search (publisher, isbn),
        // never overwrite a previously-stored non-blank value with a blank from a new payload.
        String incomingIsbn = text(payload, "isbn");
        if (incomingIsbn != null && !incomingIsbn.isBlank()) {
            fact.setIsbn(incomingIsbn);
        } else if (fact.getIsbn() == null) {
            fact.setIsbn(incomingIsbn);
        }
        fact.setAggregationType(text(payload, "aggregationType"));
        String incomingPublisher = text(payload, "publisher");
        if (incomingPublisher != null && !incomingPublisher.isBlank()) {
            fact.setPublisher(incomingPublisher);
        } else if (fact.getPublisher() == null) {
            fact.setPublisher(incomingPublisher);
        }
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
        if (authorIds.size() == authorNames.size() && authorAfids.isEmpty()) {
            authorAfids = new ArrayList<>(java.util.Collections.nCopies(authorIds.size(), ""));
        }
        if (!isAuthorCatalogAligned(authorIds, authorNames, authorAfids)) {
            log.warn("Scopus fact-builder skipped ambiguous author update: source={}, sourceRecordId={}, eid={}, authorIdsCount={}, authorNamesCount={}, authorAfidsCount={}",
                    event.getSource(),
                    event.getSourceRecordId(),
                    text(payload, "eid"),
                    authorIds.size(),
                    authorNames.size(),
                    authorAfids.size());
            result.markSkipped(sample(event, "author catalog length mismatch"));
            return;
        }

        int n = authorIds.size();

        for (int i = 0; i < n; i++) {
            String authorId = trim(authorIds.get(i));
            if (isBlank(authorId)) {
                continue;
            }

            List<String> incomingAffiliationIds = distinctNonBlank(splitDash(authorAfids.get(i)));

            ScopusAuthorFact fact = state.authorById.get(authorId);
            boolean created = fact == null;
            if (created) {
                fact = new ScopusAuthorFact();
                state.authorById.put(authorId, fact);
            }
            String payloadHash = hashKey("author",
                    authorId,
                    trim(authorNames.get(i)),
                    String.join(",", incomingAffiliationIds));
            if (!created && samePayloadHash(fact.getLastPayloadHash(), payloadHash)) {
                refreshLineageForReplay(fact, event);
                state.pendingAuthorSaves.put(authorId, fact);
                continue;
            }

            Instant now = Instant.now();
            if (fact.getCreatedAt() == null) {
                fact.setCreatedAt(now);
            }
            fact.setAuthorId(authorId);
            String incomingName = trim(authorNames.get(i));
            mergeAuthorNames(fact, incomingName);
            if (created) {
                fact.setAffiliationIds(incomingAffiliationIds);
            } else if (!incomingAffiliationIds.isEmpty()) {
                fact.setAffiliationIds(mergeDistinctStable(fact.getAffiliationIds(), incomingAffiliationIds));
            }
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
        List<String> names = splitSemicolonDecoded(text(payload, "affilname"));
        List<String> cities = splitSemicolon(text(payload, "affiliation_city"));
        List<String> countries = splitSemicolon(text(payload, "affiliation_country"));
        if (!isAffiliationCatalogAligned(afids, names, cities, countries)) {
            log.warn("Scopus fact-builder skipped ambiguous affiliation update: source={}, sourceRecordId={}, eid={}, afidCount={}, affilnameCount={}, cityCount={}, countryCount={}",
                    event.getSource(),
                    event.getSourceRecordId(),
                    text(payload, "eid"),
                    afids.size(),
                    names.size(),
                    cities.size(),
                    countries.size());
            result.markSkipped(sample(event, "affiliation catalog length mismatch"));
            return;
        }

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
                refreshLineageForReplay(fact, event);
                state.pendingAffiliationSaves.put(afid, fact);
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
            refreshLineageForReplay(fact, event);
            state.pendingFundingSaves.put(fundingKey, fact);
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

    private void applyLineage(HasLineageFields fact, ScopusImportEvent event) {
        FactBuilderSupport.applyLineage(fact, event);
    }

    private void refreshLineageForReplay(HasLineageFields fact, ScopusImportEvent event) {
        applyLineage(fact, event);
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
        String[] raw = value.split(";", -1);
        List<String> out = new ArrayList<>(raw.length);
        for (String part : raw) {
            out.add(trim(part));
        }
        return out;
    }

    private List<String> splitSemicolonDecoded(String value) {
        return splitSemicolon(decodeHtmlEntities(value));
    }

    private List<String> splitAuthKeywords(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        String[] raw = value.split("\\|", -1);
        List<String> out = new ArrayList<>(raw.length);
        for (String part : raw) {
            String trimmed = trim(part);
            if (!isBlank(trimmed)) {
                out.add(trimmed);
            }
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

    private List<String> mergeDistinctStable(List<String> existingValues, List<String> incomingValues) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(distinctNonBlank(existingValues));
        out.addAll(distinctNonBlank(incomingValues));
        return new ArrayList<>(out);
    }

    private void mergeAuthorNames(ScopusAuthorFact fact, String incomingName) {
        if (fact == null) {
            return;
        }
        String currentName = trim(fact.getName());
        LinkedHashMap<String, String> observedNames = new LinkedHashMap<>();
        rememberName(observedNames, currentName);
        if (fact.getAlternativeNames() != null) {
            fact.getAlternativeNames().forEach(name -> rememberName(observedNames, name));
        }
        rememberName(observedNames, incomingName);

        String preferredName = !isBlank(incomingName)
                ? observedNames.get(normalizeForNameMerge(incomingName))
                : observedNames.getOrDefault(normalizeForNameMerge(currentName), currentName);
        fact.setName(trim(preferredName));

        List<String> alternativeNames = new ArrayList<>();
        String preferredKey = normalizeForNameMerge(preferredName);
        for (Map.Entry<String, String> entry : observedNames.entrySet()) {
            if (!entry.getKey().equals(preferredKey)) {
                alternativeNames.add(entry.getValue());
            }
        }
        fact.setAlternativeNames(alternativeNames);
    }

    private void rememberName(Map<String, String> observedNames, String candidate) {
        String normalized = normalizeForNameMerge(candidate);
        if (!normalized.isBlank()) {
            observedNames.putIfAbsent(normalized, trim(candidate));
        }
    }

    private String normalizeForNameMerge(String value) {
        if (isBlank(value)) {
            return "";
        }
        String decomposed = Normalizer.normalize(trim(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String arrayValue(List<String> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return "";
        }
        return trim(values.get(index));
    }

    private boolean isAuthorCatalogAligned(List<String> authorIds, List<String> authorNames, List<String> authorAfids) {
        return authorIds.size() == authorNames.size() && authorIds.size() == authorAfids.size();
    }

    private boolean isAffiliationCatalogAligned(
            List<String> afids,
            List<String> names,
            List<String> cities,
            List<String> countries
    ) {
        return afids.size() == names.size()
                && afids.size() == cities.size()
                && afids.size() == countries.size();
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

    private String decodeHtmlEntities(String value) {
        if (isBlank(value)) {
            return "";
        }
        return Parser.unescapeEntities(value, false);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isUserDefinedSource(String source) {
        String normalized = trim(source).toUpperCase(Locale.ROOT);
        return UserDefinedWizardOnboardingContract.SOURCE.equals(normalized)
                || SOURCE_USER_PUBLICATION_WIZARD.equals(normalized);
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

    private record ForumWorkItem(ScopusImportEvent event, JsonNode payload) {
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
