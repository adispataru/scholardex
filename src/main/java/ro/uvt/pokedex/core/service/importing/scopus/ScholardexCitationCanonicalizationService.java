package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScholardexCitationCanonicalizationService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexCitationCanonicalizationService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1_000;
    private static final String DEFAULT_SOURCE_VERSION = "scopus-citation-facts-v1";
    private static final String PIPELINE_KEY = ScholardexCanonicalBuildCheckpointService.CITATION_PIPELINE_KEY;
    private static final String STATUS_OPEN = "OPEN";
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-citation-bridge";
    private static final String REASON_UNRESOLVED_CITED = "UNRESOLVED_CITED_PUBLICATION";
    private static final String REASON_UNRESOLVED_CITING = "UNRESOLVED_CITING_PUBLICATION";
    private static final String REASON_SOURCE_RECORD_COLLISION = "CITATION_SOURCE_RECORD_COLLISION";

    private final ScopusCitationFactRepository scopusCitationFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexCitationFactRepository scholardexCitationFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    private final ScholardexCanonicalBuildCheckpointService checkpointService;
    private final ScopusTouchQueueService touchQueueService;
    @Value("${scopus.canonical.telemetry.heartbeat-seconds:10}")
    private long heartbeatSeconds;
    @Value("${scopus.canonical.telemetry.load-progress-record-interval:10000}")
    private int loadProgressRecordInterval;

    public ImportProcessingResult rebuildCanonicalCitationFactsFromScopusFacts() {
        return rebuildCanonicalCitationFactsFromScopusFacts(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult rebuildCanonicalCitationFactsFromScopusFacts(CanonicalBuildOptions options) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        CanonicalBuildOptions effectiveOptions = options == null ? CanonicalBuildOptions.defaults() : options;
        int chunkSize = effectiveOptions.chunkSizeOverride() == null || effectiveOptions.chunkSizeOverride() <= 0
                ? DEFAULT_CHUNK_SIZE
                : effectiveOptions.chunkSizeOverride();
        log.info("Scholardex citation canonicalization phase started: mode={} fullRescan={} chunkSize={} useCheckpoint={}",
                effectiveOptions.incremental() ? "INCREMENTAL" : "FULL",
                effectiveOptions.fullRescan(),
                chunkSize,
                effectiveOptions.useCheckpoint());
        List<ScopusCitationFact> sourceFacts = loadSourceFacts(effectiveOptions);
        log.info("Scholardex citation canonicalization sort started: records={}", sourceFacts.size());
        sourceFacts.sort(Comparator
                .comparing(ScopusCitationFact::getCitedEid, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusCitationFact::getCitingEid, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusCitationFact::getSourceRecordId, Comparator.nullsLast(String::compareTo)));
        log.info("Scholardex citation canonicalization sort completed: records={}", sourceFacts.size());
        int total = sourceFacts.size();
        int totalBatches = total == 0 ? 0 : ((total - 1) / chunkSize) + 1;
        Optional<ScholardexCanonicalBuildCheckpoint> checkpoint = effectiveOptions.useCheckpoint()
                ? checkpointService.readCheckpoint(PIPELINE_KEY)
                : Optional.empty();
        int checkpointLastCompletedBatch = checkpoint.map(ScholardexCanonicalBuildCheckpoint::getLastCompletedBatch).orElse(-1);
        int startBatch = normalizeStartBatch(effectiveOptions.startBatchOverride(), checkpointLastCompletedBatch, effectiveOptions.useCheckpoint());
        boolean resumedFromCheckpoint = effectiveOptions.useCheckpoint() && effectiveOptions.startBatchOverride() == null && checkpointLastCompletedBatch >= 0;
        String runId = UUID.randomUUID().toString();
        String sourceVersion = isBlank(effectiveOptions.sourceVersionOverride()) ? DEFAULT_SOURCE_VERSION : effectiveOptions.sourceVersionOverride();
        long startedAtNanos = System.nanoTime();

        result.setStartBatch(startBatch);
        result.setTotalBatches(totalBatches);
        result.setResumedFromCheckpoint(resumedFromCheckpoint);
        result.setCheckpointLastCompletedBatch(checkpointLastCompletedBatch);

        if (startBatch >= totalBatches) {
            result.setEndBatch(startBatch);
            result.setBatchesProcessed(0);
            log.info("Scholardex citation canonicalization skipped: totalRecords={}, totalBatches={}, startBatch={}, checkpointLastCompletedBatch={}",
                    total, totalBatches, startBatch, checkpointLastCompletedBatch);
            return result;
        }

        int batchesProcessed = 0;
        for (int batchIndex = startBatch; batchIndex < totalBatches; batchIndex++) {
            int from = batchIndex * chunkSize;
            int to = Math.min(total, from + chunkSize);
            int chunkNo = batchIndex + 1;
            int importedBefore = result.getImportedCount();
            int updatedBefore = result.getUpdatedCount();
            int skippedBefore = result.getSkippedCount();
            int errorsBefore = result.getErrorCount();

            CitationChunkOutcome outcome = processChunk(sourceFacts.subList(from, to), result, chunkNo, totalBatches, total);
            CanonicalBuildChunkTimings timings = outcome.timings();
            batchesProcessed++;
            result.setEndBatch(batchIndex);
            result.setBatchesProcessed(batchesProcessed);

            if (effectiveOptions.useCheckpoint()) {
                checkpointService.upsertCheckpoint(
                        PIPELINE_KEY,
                        batchIndex,
                        chunkSize,
                        lastRecordKey(sourceFacts.subList(from, to)),
                        runId,
                        sourceVersion
                );
            }

            log.info("Scholardex citation canonicalization chunk {} complete [batch={} / totalBatches={}]: records={} imported={} updated={} skipped={} errors={} timingsMs[preload={}, resolve={}, upsert={}, save={}, total={}]",
                    chunkNo,
                    chunkNo,
                    totalBatches,
                    to - from,
                    result.getImportedCount() - importedBefore,
                    result.getUpdatedCount() - updatedBefore,
                    result.getSkippedCount() - skippedBefore,
                    result.getErrorCount() - errorsBefore,
                    timings.preloadMs(),
                    timings.resolveMs(),
                    timings.upsertMs(),
                    timings.saveMs(),
                    timings.totalMs());
            log.info("Scholardex citation canonicalization chunk {} writes: citationFacts={} sourceLinks[linked={}, unmatched={}, conflict={}, skipped={}] conflicts={}",
                    chunkNo,
                    outcome.citationFactWrites(),
                    outcome.sourceLinkLinkedWrites(),
                    outcome.sourceLinkUnmatchedWrites(),
                    outcome.sourceLinkConflictWrites(),
                    outcome.sourceLinkSkippedWrites(),
                    outcome.conflictsWritten());
        }

        log.info("Scholardex citation canonicalization summary: processed={}, imported={}, updated={}, skipped={}, errors={}, batchesProcessed={}, totalBatches={}, resumedFromCheckpoint={}, checkpointLastCompletedBatch={}, totalMs={}",
                result.getProcessedCount(),
                result.getImportedCount(),
                result.getUpdatedCount(),
                result.getSkippedCount(),
                result.getErrorCount(),
                result.getBatchesProcessed(),
                result.getTotalBatches(),
                resumedFromCheckpoint,
                checkpointLastCompletedBatch,
                nanosToMillis(System.nanoTime() - startedAtNanos));
        H19CanonicalMetrics.recordCanonicalBuildRun(
                "citation",
                "SCOPUS",
                result.getErrorCount() > 0 ? "failure" : "success",
                System.nanoTime() - startedAtNanos
        );
        return result;
    }

    private CitationChunkOutcome processChunk(
            List<ScopusCitationFact> chunk,
            ImportProcessingResult result,
            int chunkNo,
            int totalBatches,
            int totalRecords
    ) {
        long chunkStartedAtNanos = System.nanoTime();
        ChunkContext context = preloadChunkContext(chunk);
        long preloadFinishedAtNanos = System.nanoTime();
        long heartbeatIntervalNanos = Math.max(1L, heartbeatSeconds) * 1_000_000_000L;
        long lastHeartbeatAtNanos = chunkStartedAtNanos;
        int chunkProcessed = 0;
        for (ScopusCitationFact sourceFact : chunk) {
            result.markProcessed();
            resolveCitationIntoContext(sourceFact, result, context);
            chunkProcessed++;
            long now = System.nanoTime();
            if (now - lastHeartbeatAtNanos >= heartbeatIntervalNanos) {
                long elapsedMs = nanosToMillis(now - chunkStartedAtNanos);
                long ratePerSec = elapsedMs <= 0 ? 0 : Math.round((chunkProcessed * 1000.0d) / elapsedMs);
                log.info("Scholardex citation canonicalization heartbeat [batch={} / totalBatches={}]: chunkProcessed={} totalProcessed={} totalRecords={} elapsedMs={} ratePerSec={}",
                        chunkNo,
                        totalBatches,
                        chunkProcessed,
                        result.getProcessedCount(),
                        totalRecords,
                        elapsedMs,
                        ratePerSec);
                lastHeartbeatAtNanos = now;
            }
        }
        long resolveFinishedAtNanos = System.nanoTime();

        int citationFactWrites = 0;
        int sourceLinkLinkedWrites = 0;
        int sourceLinkUnmatchedWrites = 0;
        int sourceLinkConflictWrites = 0;
        int sourceLinkSkippedWrites = 0;
        int conflictsWritten = 0;

        if (!context.pendingCitationFacts.isEmpty()) {
            scholardexCitationFactRepository.saveAll(context.pendingCitationFacts.values());
            citationFactWrites = context.pendingCitationFacts.size();
        }
        if (!context.pendingSourceLinkCommands.isEmpty()) {
            ScholardexSourceLinkService.BatchWriteResult sourceLinkResults =
                    sourceLinkService.batchUpsertWithState(context.pendingSourceLinkCommands.values(), context.sourceLinkCache, false);
            for (ScholardexSourceLinkService.SourceLinkBatchItemResult item : sourceLinkResults.results()) {
                if (!item.accepted()) {
                    continue;
                }
                String state = item.link().getLinkState();
                if (ScholardexSourceLinkService.STATE_LINKED.equals(state)) {
                    sourceLinkLinkedWrites++;
                } else if (ScholardexSourceLinkService.STATE_UNMATCHED.equals(state)) {
                    sourceLinkUnmatchedWrites++;
                } else if (ScholardexSourceLinkService.STATE_CONFLICT.equals(state)) {
                    sourceLinkConflictWrites++;
                } else if (ScholardexSourceLinkService.STATE_SKIPPED.equals(state)) {
                    sourceLinkSkippedWrites++;
                }
            }
        }
        if (!context.pendingConflicts.isEmpty()) {
            scholardexIdentityConflictRepository.saveAll(context.pendingConflicts.values());
            conflictsWritten = context.pendingConflicts.size();
        }
        long saveFinishedAtNanos = System.nanoTime();

        return new CitationChunkOutcome(
                new CanonicalBuildChunkTimings(
                nanosToMillis(preloadFinishedAtNanos - chunkStartedAtNanos),
                nanosToMillis(resolveFinishedAtNanos - preloadFinishedAtNanos),
                0L,
                nanosToMillis(saveFinishedAtNanos - resolveFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - chunkStartedAtNanos)
                ),
                citationFactWrites,
                sourceLinkLinkedWrites,
                sourceLinkUnmatchedWrites,
                sourceLinkConflictWrites,
                sourceLinkSkippedWrites,
                conflictsWritten
        );
    }

    private void resolveCitationIntoContext(
            ScopusCitationFact sourceFact,
            ImportProcessingResult result,
            ChunkContext context
    ) {
        if (sourceFact == null) {
            result.markSkipped("null-scopus-citation-fact");
            return;
        }
        String source = normalizeBlank(sourceFact.getSource());
        String sourceRecordId = normalizeBlank(sourceFact.getSourceRecordId());
        if (sourceRecordId == null) {
            sourceRecordId = normalizeBlank(sourceFact.getCitedEid()) + "->" + normalizeBlank(sourceFact.getCitingEid());
        }
        String citedPublicationId = context.publicationIdByEid.get(sourceFact.getCitedEid());
        if (isBlank(citedPublicationId)) {
            queueConflict(context, sourceFact, sourceRecordId, REASON_UNRESOLVED_CITED, List.of());
            result.markSkipped("unresolved-cited-eid=" + sourceFact.getCitedEid());
            return;
        }
        String citingPublicationId = context.publicationIdByEid.get(sourceFact.getCitingEid());
        if (isBlank(citingPublicationId)) {
            queueConflict(context, sourceFact, sourceRecordId, REASON_UNRESOLVED_CITING, List.of(citedPublicationId));
            result.markSkipped("unresolved-citing-eid=" + sourceFact.getCitingEid());
            return;
        }

        Optional<ScholardexSourceLink> existingSourceLink = resolveFromChunkSourceLinks(context, source, sourceRecordId);
        String edgeId = buildCanonicalCitationId(citedPublicationId, citingPublicationId, source);
        ScholardexCitationFact existingEdge = context.citationById.get(edgeId);
        if (existingSourceLink.isPresent()
                && !isBlank(existingSourceLink.get().getCanonicalEntityId())
                && existingEdge != null
                && !existingSourceLink.get().getCanonicalEntityId().equals(existingEdge.getId())) {
            queueConflict(context, sourceFact, sourceRecordId, REASON_SOURCE_RECORD_COLLISION, List.of(existingEdge.getId()));
            result.markSkipped("citation-source-record-collision=" + sourceRecordId);
            return;
        }

        ScholardexCitationFact canonicalFact = existingEdge == null ? new ScholardexCitationFact() : existingEdge;
        Instant now = Instant.now();
        if (canonicalFact.getCreatedAt() == null) {
            canonicalFact.setCreatedAt(now);
        }
        canonicalFact.setId(edgeId);
        canonicalFact.setCitedPublicationId(citedPublicationId);
        canonicalFact.setCitingPublicationId(citingPublicationId);
        canonicalFact.setSource(source);
        canonicalFact.setSourceRecordId(sourceRecordId);
        canonicalFact.setSourceEventId(sourceFact.getSourceEventId());
        canonicalFact.setSourceBatchId(sourceFact.getSourceBatchId());
        canonicalFact.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        canonicalFact.setUpdatedAt(now);
        context.citationById.put(edgeId, canonicalFact);
        context.pendingCitationFacts.put(edgeId, canonicalFact);
        queueSourceLinkCommand(context, canonicalFact);

        if (existingEdge != null) {
            result.markUpdated();
        } else {
            result.markImported();
        }
    }

    private ChunkContext preloadChunkContext(List<ScopusCitationFact> chunk) {
        ChunkContext context = new ChunkContext();
        Set<String> eids = new LinkedHashSet<>();
        Set<String> sourceRecordIds = new LinkedHashSet<>();
        for (ScopusCitationFact sourceFact : chunk) {
            if (sourceFact == null) {
                continue;
            }
            String citedEid = normalizeBlank(sourceFact.getCitedEid());
            String citingEid = normalizeBlank(sourceFact.getCitingEid());
            if (citedEid != null) {
                eids.add(citedEid);
            }
            if (citingEid != null) {
                eids.add(citingEid);
            }
            String sourceRecordId = normalizeBlank(sourceFact.getSourceRecordId());
            if (sourceRecordId == null) {
                sourceRecordId = normalizeBlank(sourceFact.getCitedEid()) + "->" + normalizeBlank(sourceFact.getCitingEid());
            }
            sourceRecordIds.add(sourceRecordId);
        }

        if (!eids.isEmpty()) {
            List<ScholardexPublicationFact> publications = scholardexPublicationFactRepository.findAllByEidIn(eids);
            for (ScholardexPublicationFact publication : publications) {
                if (!isBlank(publication.getEid()) && !isBlank(publication.getId())) {
                    context.publicationIdByEid.put(publication.getEid(), publication.getId());
                }
            }
        }

        Set<String> edgeIds = new LinkedHashSet<>();
        for (ScopusCitationFact sourceFact : chunk) {
            if (sourceFact == null) {
                continue;
            }
            String source = normalizeBlank(sourceFact.getSource());
            String citedPublicationId = context.publicationIdByEid.get(sourceFact.getCitedEid());
            String citingPublicationId = context.publicationIdByEid.get(sourceFact.getCitingEid());
            if (!isBlank(source) && !isBlank(citedPublicationId) && !isBlank(citingPublicationId)) {
                edgeIds.add(buildCanonicalCitationId(citedPublicationId, citingPublicationId, source));
            }
        }
        if (!edgeIds.isEmpty()) {
            for (ScholardexCitationFact existing : scholardexCitationFactRepository.findAllById(edgeIds)) {
                if (existing != null && !isBlank(existing.getId())) {
                    context.citationById.put(existing.getId(), existing);
                }
            }
        }

        if (!sourceRecordIds.isEmpty()) {
            List<ScholardexSourceLink> sourceLinks =
                    sourceLinkService.findByEntityTypeAndSourceRecordIds(ScholardexEntityType.CITATION, sourceRecordIds);
            for (ScholardexSourceLink sourceLink : sourceLinks) {
                context.sourceLinkCache.put(toSourceLinkKey(sourceLink), sourceLink);
            }
        }
        return context;
    }

    private Optional<ScholardexSourceLink> resolveFromChunkSourceLinks(
            ChunkContext context,
            String source,
            String sourceRecordId
    ) {
        if (context == null || isBlank(source) || isBlank(sourceRecordId)) {
            return Optional.empty();
        }
        String normalizedSource = sourceLinkService.normalizeSource(source);
        if (!isBlank(normalizedSource)) {
            ScholardexSourceLinkService.SourceLinkKey normalizedKey = ScholardexSourceLinkService.SourceLinkKey.of(
                    ScholardexEntityType.CITATION,
                    normalizedSource,
                    sourceRecordId
            );
            ScholardexSourceLink normalizedLink = context.sourceLinkCache.get(normalizedKey);
            if (normalizedLink != null) {
                return Optional.of(normalizedLink);
            }
        }
        String rawSource = normalizeBlank(source);
        if (!isBlank(rawSource) && !rawSource.equals(normalizedSource)) {
            ScholardexSourceLinkService.SourceLinkKey rawKey = ScholardexSourceLinkService.SourceLinkKey.of(
                    ScholardexEntityType.CITATION,
                    rawSource,
                    sourceRecordId
            );
            ScholardexSourceLink rawLink = context.sourceLinkCache.get(rawKey);
            if (rawLink != null) {
                return Optional.of(rawLink);
            }
        }
        return Optional.empty();
    }

    private ScholardexSourceLinkService.SourceLinkKey toSourceLinkKey(ScholardexSourceLink sourceLink) {
        return ScholardexSourceLinkService.SourceLinkKey.of(
                sourceLink.getEntityType(),
                sourceLink.getSource(),
                sourceLink.getSourceRecordId()
        );
    }

    private void queueSourceLinkCommand(ChunkContext context, ScholardexCitationFact citationFact) {
        if (isBlank(citationFact.getSource()) || isBlank(citationFact.getSourceRecordId())) {
            return;
        }
        String key = normalizeToken(citationFact.getSource()) + "|" + normalizeToken(citationFact.getSourceRecordId());
        context.pendingSourceLinkCommands.put(key, new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                ScholardexEntityType.CITATION,
                citationFact.getSource(),
                citationFact.getSourceRecordId(),
                citationFact.getId(),
                ScholardexSourceLinkService.STATE_LINKED,
                LINK_REASON_SCOPUS_BRIDGE,
                citationFact.getSourceEventId(),
                citationFact.getSourceBatchId(),
                citationFact.getSourceCorrelationId(),
                false
        ));
    }

    private void queueConflict(
            ChunkContext context,
            ScopusCitationFact sourceFact,
            String sourceRecordId,
            String reasonCode,
            List<String> candidates
    ) {
        String incomingSource = normalizeBlank(sourceFact.getSource());
        if (incomingSource == null) {
            incomingSource = "UNKNOWN";
        }
        if (sourceRecordId == null) {
            sourceRecordId = "unknown";
        }
        String key = normalizeToken(incomingSource) + "|" + normalizeToken(sourceRecordId) + "|" + normalizeToken(reasonCode);
        ScholardexIdentityConflict conflict = context.pendingConflicts.get(key);
        if (conflict == null) {
            conflict = new ScholardexIdentityConflict();
            conflict.setEntityType(ScholardexEntityType.CITATION);
            conflict.setIncomingSource(incomingSource);
            conflict.setIncomingSourceRecordId(sourceRecordId);
            conflict.setReasonCode(reasonCode);
            conflict.setStatus(STATUS_OPEN);
            conflict.setDetectedAt(Instant.now());
            context.pendingConflicts.put(key, conflict);
        }
        conflict.setCandidateCanonicalIds(candidates == null ? List.of() : new ArrayList<>(candidates));
        conflict.setSourceEventId(sourceFact.getSourceEventId());
        conflict.setSourceBatchId(sourceFact.getSourceBatchId());
        conflict.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        H19CanonicalMetrics.recordConflictCreated(ScholardexEntityType.CITATION.name(), incomingSource, reasonCode);
    }

    private void saveConflict(
            ScopusCitationFact sourceFact,
            String sourceRecordId,
            String reasonCode,
            List<String> candidates
    ) {
        String incomingSource = normalizeBlank(sourceFact.getSource());
        if (incomingSource == null) {
            incomingSource = "UNKNOWN";
        }
        if (sourceRecordId == null) {
            sourceRecordId = "unknown";
        }
        ScholardexIdentityConflict conflict = scholardexIdentityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        ScholardexEntityType.CITATION,
                        incomingSource,
                        sourceRecordId,
                        reasonCode,
                        STATUS_OPEN
                ).orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(ScholardexEntityType.CITATION);
        conflict.setIncomingSource(incomingSource);
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(reasonCode);
        conflict.setStatus(STATUS_OPEN);
        conflict.setCandidateCanonicalIds(candidates == null ? List.of() : new ArrayList<>(candidates));
        conflict.setSourceEventId(sourceFact.getSourceEventId());
        conflict.setSourceBatchId(sourceFact.getSourceBatchId());
        conflict.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        scholardexIdentityConflictRepository.save(conflict);
        H19CanonicalMetrics.recordConflictCreated(ScholardexEntityType.CITATION.name(), incomingSource, reasonCode);
    }

    private String buildCanonicalCitationId(String citedPublicationId, String citingPublicationId, String source) {
        return "scit_" + shortHash(
                normalizeBlank(citedPublicationId) + "|" + normalizeBlank(citingPublicationId) + "|" + normalizeToken(source)
        );
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeToken(String value) {
        String normalized = normalizeBlank(value);
        return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private int normalizeStartBatch(Integer startBatchOverride, int checkpointLastCompletedBatch, boolean useCheckpoint) {
        if (startBatchOverride != null) {
            return Math.max(0, startBatchOverride);
        }
        if (useCheckpoint && checkpointLastCompletedBatch >= 0) {
            return Math.max(0, checkpointLastCompletedBatch + 1);
        }
        return 0;
    }

    private String lastRecordKey(List<ScopusCitationFact> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return null;
        }
        ScopusCitationFact last = chunk.get(chunk.size() - 1);
        String sourceRecordId = normalizeBlank(last.getSourceRecordId());
        if (sourceRecordId != null) {
            return sourceRecordId;
        }
        return normalizeBlank(last.getCitedEid()) + "->" + normalizeBlank(last.getCitingEid());
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private List<ScopusCitationFact> loadSourceFacts(CanonicalBuildOptions options) {
        if (!options.fullRescan() && options.incremental()) {
            long startedAtNanos = System.nanoTime();
            log.info("Scholardex citation canonicalization source load started: mode=incremental drainQueues={}", options.drainQueues());
            List<ScopusTouchQueueService.CitationEdge> touchedEdges = touchQueueService.consumeCitationEdges(options.drainQueues());
            if (!touchedEdges.isEmpty()) {
                LinkedHashMap<String, ScopusTouchQueueService.CitationEdge> deduped = new LinkedHashMap<>();
                LinkedHashSet<String> citedEids = new LinkedHashSet<>();
                LinkedHashSet<String> citingEids = new LinkedHashSet<>();
                for (ScopusTouchQueueService.CitationEdge edge : touchedEdges) {
                    if (edge == null || isBlank(edge.citedEid()) || isBlank(edge.citingEid())) {
                        continue;
                    }
                    String key = edge.citedEid() + "->" + edge.citingEid();
                    deduped.putIfAbsent(key, edge);
                    citedEids.add(edge.citedEid());
                    citingEids.add(edge.citingEid());
                }
                if (!deduped.isEmpty()) {
                    List<ScopusCitationFact> candidates = scopusCitationFactRepository.findByCitedEidInAndCitingEidIn(citedEids, citingEids);
                    List<ScopusCitationFact> result = new ArrayList<>();
                    for (ScopusCitationFact fact : candidates) {
                        String key = fact.getCitedEid() + "->" + fact.getCitingEid();
                        if (deduped.containsKey(key)) {
                            result.add(fact);
                        }
                    }
                    log.info("Scholardex citation canonicalization source load completed: mode=incremental records={} elapsedMs={}",
                            result.size(),
                            nanosToMillis(System.nanoTime() - startedAtNanos));
                    return result;
                }
            }
            log.info("Scholardex citation canonicalization incremental run has empty touch queue; skipping source scan.");
            return new ArrayList<>();
        }
        long startedAtNanos = System.nanoTime();
        long totalRecords = scopusCitationFactRepository.count();
        int pageSize = Math.max(1_000, Math.min(10_000, loadProgressRecordInterval));
        log.info("Scholardex citation canonicalization source load started: mode=full-rescan totalRecords={} pageSize={}", totalRecords, pageSize);
        List<ScopusCitationFact> out = new ArrayList<>();
        long loaded = 0L;
        long nextLogAt = Math.max(1L, loadProgressRecordInterval);
        int pageNo = 0;
        while (true) {
            Page<ScopusCitationFact> page = scopusCitationFactRepository.findAll(PageRequest.of(pageNo, pageSize));
            if (page.isEmpty()) {
                break;
            }
            out.addAll(page.getContent());
            loaded += page.getNumberOfElements();
            if (loaded >= nextLogAt || !page.hasNext()) {
                log.info("Scholardex citation canonicalization source load progress: loaded={} totalRecords={} elapsedMs={}",
                        loaded,
                        totalRecords,
                        nanosToMillis(System.nanoTime() - startedAtNanos));
                nextLogAt += Math.max(1L, loadProgressRecordInterval);
            }
            if (!page.hasNext()) {
                break;
            }
            pageNo++;
        }
        log.info("Scholardex citation canonicalization source load completed: mode=full-rescan records={} elapsedMs={}",
                out.size(),
                nanosToMillis(System.nanoTime() - startedAtNanos));
        return out;
    }

    private static class ChunkContext {
        private final Map<String, String> publicationIdByEid = new LinkedHashMap<>();
        private final Map<String, ScholardexCitationFact> citationById = new LinkedHashMap<>();
        private final Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> sourceLinkCache = new LinkedHashMap<>();
        private final Map<String, ScholardexCitationFact> pendingCitationFacts = new LinkedHashMap<>();
        private final Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingSourceLinkCommands = new LinkedHashMap<>();
        private final Map<String, ScholardexIdentityConflict> pendingConflicts = new LinkedHashMap<>();
    }

    private record CitationChunkOutcome(
            CanonicalBuildChunkTimings timings,
            int citationFactWrites,
            int sourceLinkLinkedWrites,
            int sourceLinkUnmatchedWrites,
            int sourceLinkConflictWrites,
            int sourceLinkSkippedWrites,
            int conflictsWritten
    ) {}
}
