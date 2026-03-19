package ro.uvt.pokedex.core.service.importing.scopus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.isBlank;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.nanosToMillis;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.normalizeStartBatch;

/**
 * Template for the canonicalization pipeline shared by all four entity-specific services
 * (Affiliation, Author, Citation, Publication).
 *
 * <p>Subclasses supply the entity-specific logic via abstract hooks; this base class owns
 * the rebuild loop, checkpoint management, heartbeat logging, and metrics recording.</p>
 *
 * @param <S> the source fact type (e.g. {@code ScopusAffiliationFact})
 * @param <C> the chunk context type, created per batch to hold caches and pending writes
 */
public abstract class AbstractCanonicalizationService<S, C> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    protected final ScholardexSourceLinkService sourceLinkService;
    protected final ScholardexIdentityConflictRepository identityConflictRepository;
    protected final ScholardexCanonicalBuildCheckpointService checkpointService;
    protected final ScopusTouchQueueService touchQueueService;

    protected AbstractCanonicalizationService(
            ScholardexSourceLinkService sourceLinkService,
            ScholardexIdentityConflictRepository identityConflictRepository,
            ScholardexCanonicalBuildCheckpointService checkpointService,
            ScopusTouchQueueService touchQueueService
    ) {
        this.sourceLinkService = sourceLinkService;
        this.identityConflictRepository = identityConflictRepository;
        this.checkpointService = checkpointService;
        this.touchQueueService = touchQueueService;
    }

    // ── Abstract hooks ──────────────────────────────────────────────────────

    /** The pipeline key used for checkpoint storage. */
    protected abstract String getPipelineKey();

    /** The entity type label used in log messages and metrics (e.g. "affiliation"). */
    protected abstract String getEntityTypeLabel();

    /** The canonical entity type enum value. */
    protected abstract ScholardexEntityType getEntityType();

    /** Default chunk size when no override is provided. */
    protected abstract int getDefaultChunkSize();

    /** Default source version string when no override is provided. */
    protected abstract String getDefaultSourceVersion();

    /** Heartbeat interval in seconds (typically wired from a @Value property). */
    protected abstract long getHeartbeatSeconds();

    /** Load source facts according to the given options. */
    protected abstract List<S> loadSourceFacts(CanonicalBuildOptions options);

    /** Sort the loaded source facts in the order they should be processed. */
    protected abstract void sortSourceFacts(List<S> facts);

    /** Extract a record key from the last element of a chunk (for checkpoint). */
    protected abstract String lastRecordKey(List<S> chunk);

    /** Create a fresh chunk context for a new batch. Replaces the old clearChunkContext(). */
    protected abstract C createChunkContext();

    /** Preload caches/context needed for this chunk. Called once per batch. */
    protected abstract void preloadChunkContext(List<S> chunk, C context);

    /** Process a single source fact within the current chunk context. */
    protected abstract void processSourceFact(S fact, ImportProcessingResult result, C context);

    /** Flush all pending writes (facts, source links, conflicts) for the current chunk. */
    protected abstract CanonicalBuildChunkTimings flushPendingWrites(long chunkStartedAtNanos, long preloadFinishedAtNanos, long resolveFinishedAtNanos, C context);

    /** Optional hook called after the standard chunk log line. Override to log extra per-chunk stats. */
    protected void afterChunkLogged(int chunkNo, C context) {
        // default no-op
    }

    // ── Rebuild loop (shared) ───────────────────────────────────────────────

    public ImportProcessingResult rebuild(CanonicalBuildOptions options) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        CanonicalBuildOptions effectiveOptions = options == null ? CanonicalBuildOptions.defaults() : options;
        int chunkSize = effectiveOptions.chunkSizeOverride() == null || effectiveOptions.chunkSizeOverride() <= 0
                ? getDefaultChunkSize()
                : effectiveOptions.chunkSizeOverride();

        String label = getEntityTypeLabel();
        log.info("Scholardex {} canonicalization phase started: mode={} fullRescan={} chunkSize={} useCheckpoint={}",
                label,
                effectiveOptions.incremental() ? "INCREMENTAL" : "FULL",
                effectiveOptions.fullRescan(),
                chunkSize,
                effectiveOptions.useCheckpoint());

        List<S> sourceFacts = loadSourceFacts(effectiveOptions);
        log.info("Scholardex {} canonicalization sort started: records={}", label, sourceFacts.size());
        sortSourceFacts(sourceFacts);
        log.info("Scholardex {} canonicalization sort completed: records={}", label, sourceFacts.size());

        int total = sourceFacts.size();
        int totalBatches = total == 0 ? 0 : ((total - 1) / chunkSize) + 1;

        Optional<ScholardexCanonicalBuildCheckpoint> checkpoint = effectiveOptions.useCheckpoint()
                ? checkpointService.readCheckpoint(getPipelineKey())
                : Optional.empty();
        int checkpointLastCompletedBatch = checkpoint.map(ScholardexCanonicalBuildCheckpoint::getLastCompletedBatch).orElse(-1);
        int startBatch = normalizeStartBatch(effectiveOptions.startBatchOverride(), checkpointLastCompletedBatch, effectiveOptions.useCheckpoint());
        boolean resumedFromCheckpoint = effectiveOptions.useCheckpoint() && effectiveOptions.startBatchOverride() == null && checkpointLastCompletedBatch >= 0;
        String runId = UUID.randomUUID().toString();
        String sourceVersion = isBlank(effectiveOptions.sourceVersionOverride()) ? getDefaultSourceVersion() : effectiveOptions.sourceVersionOverride();
        long startedAtNanos = System.nanoTime();

        result.setStartBatch(startBatch);
        result.setTotalBatches(totalBatches);
        result.setResumedFromCheckpoint(resumedFromCheckpoint);
        result.setCheckpointLastCompletedBatch(checkpointLastCompletedBatch);

        if (startBatch >= totalBatches) {
            result.setEndBatch(startBatch);
            result.setBatchesProcessed(0);
            log.info("Scholardex {} canonicalization skipped: totalRecords={}, totalBatches={}, startBatch={}, checkpointLastCompletedBatch={}",
                    label, total, totalBatches, startBatch, checkpointLastCompletedBatch);
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

            C chunkContext = createChunkContext();
            CanonicalBuildChunkTimings timings = processChunk(sourceFacts.subList(from, to), result, chunkNo, totalBatches, total, chunkContext);
            batchesProcessed++;
            result.setEndBatch(batchIndex);
            result.setBatchesProcessed(batchesProcessed);

            if (effectiveOptions.useCheckpoint()) {
                checkpointService.upsertCheckpoint(
                        getPipelineKey(),
                        batchIndex,
                        chunkSize,
                        lastRecordKey(sourceFacts.subList(from, to)),
                        runId,
                        sourceVersion
                );
            }
            log.info("Scholardex {} canonicalization chunk {} complete [batch={} / totalBatches={}]: records={} imported={} updated={} skipped={} errors={} timingsMs[preload={}, resolve={}, upsert={}, save={}, total={}]",
                    label,
                    chunkNo, chunkNo, totalBatches,
                    to - from,
                    result.getImportedCount() - importedBefore,
                    result.getUpdatedCount() - updatedBefore,
                    result.getSkippedCount() - skippedBefore,
                    result.getErrorCount() - errorsBefore,
                    timings.preloadMs(), timings.resolveMs(), timings.upsertMs(), timings.saveMs(), timings.totalMs());
            afterChunkLogged(chunkNo, chunkContext);
        }

        log.info("Scholardex {} canonicalization summary: processed={}, imported={}, updated={}, skipped={}, errors={}, batchesProcessed={}, totalBatches={}, resumedFromCheckpoint={}, checkpointLastCompletedBatch={}, totalMs={}",
                label,
                result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                result.getSkippedCount(), result.getErrorCount(),
                result.getBatchesProcessed(), result.getTotalBatches(),
                resumedFromCheckpoint, checkpointLastCompletedBatch,
                nanosToMillis(System.nanoTime() - startedAtNanos));

        H19CanonicalMetrics.recordCanonicalBuildRun(
                label,
                "SCOPUS",
                result.getErrorCount() > 0 ? "failure" : "success",
                System.nanoTime() - startedAtNanos
        );
        return result;
    }

    private CanonicalBuildChunkTimings processChunk(
            List<S> chunk,
            ImportProcessingResult result,
            int chunkNo,
            int totalBatches,
            int totalRecords,
            C context
    ) {
        long chunkStartedAtNanos = System.nanoTime();
        preloadChunkContext(chunk, context);
        long preloadFinishedAtNanos = System.nanoTime();

        long heartbeatIntervalNanos = Math.max(1L, getHeartbeatSeconds()) * 1_000_000_000L;
        long lastHeartbeatAtNanos = chunkStartedAtNanos;
        int chunkProcessed = 0;
        String label = getEntityTypeLabel();

        for (S sourceFact : chunk) {
            result.markProcessed();
            processSourceFact(sourceFact, result, context);
            chunkProcessed++;
            long now = System.nanoTime();
            if (now - lastHeartbeatAtNanos >= heartbeatIntervalNanos) {
                long elapsedMs = nanosToMillis(now - chunkStartedAtNanos);
                long ratePerSec = elapsedMs <= 0 ? 0 : Math.round((chunkProcessed * 1000.0d) / elapsedMs);
                log.info("Scholardex {} canonicalization heartbeat [batch={} / totalBatches={}]: chunkProcessed={} totalProcessed={} totalRecords={} elapsedMs={} ratePerSec={}",
                        label, chunkNo, totalBatches,
                        chunkProcessed, result.getProcessedCount(), totalRecords,
                        elapsedMs, ratePerSec);
                lastHeartbeatAtNanos = now;
            }
        }
        long resolveFinishedAtNanos = System.nanoTime();

        return flushPendingWrites(chunkStartedAtNanos, preloadFinishedAtNanos, resolveFinishedAtNanos, context);
    }

    // ── Conflict helper ─────────────────────────────────────────────────────

    protected ScholardexIdentityConflict buildConflict(
            String source,
            String sourceRecordId,
            String reasonCode,
            List<String> candidateCanonicalIds,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId
    ) {
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        getEntityType(), source, sourceRecordId, reasonCode, CanonicalizationSupport.STATUS_OPEN
                )
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(getEntityType());
        conflict.setIncomingSource(source);
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(reasonCode);
        conflict.setStatus(CanonicalizationSupport.STATUS_OPEN);
        conflict.setCandidateCanonicalIds(candidateCanonicalIds == null ? List.of() : new ArrayList<>(candidateCanonicalIds));
        conflict.setSourceEventId(sourceEventId);
        conflict.setSourceBatchId(sourceBatchId);
        conflict.setSourceCorrelationId(sourceCorrelationId);
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        H19CanonicalMetrics.recordConflictCreated(getEntityType().name(), source, reasonCode);
        return conflict;
    }
}
