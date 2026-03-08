package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScholardexCitationCanonicalizationService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexCitationCanonicalizationService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1_000;
    private static final String DEFAULT_SOURCE_VERSION = "scopus-citation-facts-v1";
    private static final String PIPELINE_KEY = ScholardexCanonicalBuildCheckpointService.CITATION_PIPELINE_KEY;
    private static final String STATUS_OPEN = "OPEN";
    private static final String LINK_STATE_LINKED = "LINKED";
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-citation-bridge";
    private static final String REASON_UNRESOLVED_CITED = "UNRESOLVED_CITED_PUBLICATION";
    private static final String REASON_UNRESOLVED_CITING = "UNRESOLVED_CITING_PUBLICATION";
    private static final String REASON_SOURCE_RECORD_COLLISION = "CITATION_SOURCE_RECORD_COLLISION";

    private final ScopusCitationFactRepository scopusCitationFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexCitationFactRepository scholardexCitationFactRepository;
    private final ScholardexSourceLinkRepository scholardexSourceLinkRepository;
    private final ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    private final ScholardexCanonicalBuildCheckpointService checkpointService;

    public ImportProcessingResult rebuildCanonicalCitationFactsFromScopusFacts() {
        return rebuildCanonicalCitationFactsFromScopusFacts(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult rebuildCanonicalCitationFactsFromScopusFacts(CanonicalBuildOptions options) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Map<String, String> publicationIdByEid = loadPublicationIdByEid();
        List<ScopusCitationFact> sourceFacts = new ArrayList<>(scopusCitationFactRepository.findAll());
        sourceFacts.sort(Comparator
                .comparing(ScopusCitationFact::getCitedEid, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusCitationFact::getCitingEid, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusCitationFact::getSourceRecordId, Comparator.nullsLast(String::compareTo)));

        CanonicalBuildOptions effectiveOptions = options == null ? CanonicalBuildOptions.defaults() : options;
        int chunkSize = effectiveOptions.chunkSizeOverride() == null || effectiveOptions.chunkSizeOverride() <= 0
                ? DEFAULT_CHUNK_SIZE
                : effectiveOptions.chunkSizeOverride();
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

            CanonicalBuildChunkTimings timings = processChunk(sourceFacts.subList(from, to), publicationIdByEid, result);
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
        return result;
    }

    private CanonicalBuildChunkTimings processChunk(
            List<ScopusCitationFact> chunk,
            Map<String, String> publicationIdByEid,
            ImportProcessingResult result
    ) {
        long chunkStartedAtNanos = System.nanoTime();
        long preloadFinishedAtNanos = System.nanoTime();
        long resolveFinishedAtNanos = preloadFinishedAtNanos;
        for (ScopusCitationFact sourceFact : chunk) {
            result.markProcessed();
            upsertCitation(sourceFact, publicationIdByEid, result);
        }
        long upsertFinishedAtNanos = System.nanoTime();
        long saveFinishedAtNanos = upsertFinishedAtNanos;
        return new CanonicalBuildChunkTimings(
                nanosToMillis(preloadFinishedAtNanos - chunkStartedAtNanos),
                nanosToMillis(resolveFinishedAtNanos - preloadFinishedAtNanos),
                nanosToMillis(upsertFinishedAtNanos - resolveFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - upsertFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - chunkStartedAtNanos)
        );
    }

    private Map<String, String> loadPublicationIdByEid() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ScholardexPublicationFact publicationFact : scholardexPublicationFactRepository.findAll()) {
            if (!isBlank(publicationFact.getEid()) && !isBlank(publicationFact.getId())) {
                out.put(publicationFact.getEid(), publicationFact.getId());
            }
        }
        return out;
    }

    private void upsertCitation(
            ScopusCitationFact sourceFact,
            Map<String, String> publicationIdByEid,
            ImportProcessingResult result
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
        String citedPublicationId = publicationIdByEid.get(sourceFact.getCitedEid());
        if (isBlank(citedPublicationId)) {
            saveConflict(sourceFact, sourceRecordId, REASON_UNRESOLVED_CITED, List.of());
            result.markSkipped("unresolved-cited-eid=" + sourceFact.getCitedEid());
            return;
        }
        String citingPublicationId = publicationIdByEid.get(sourceFact.getCitingEid());
        if (isBlank(citingPublicationId)) {
            saveConflict(sourceFact, sourceRecordId, REASON_UNRESOLVED_CITING, List.of(citedPublicationId));
            result.markSkipped("unresolved-citing-eid=" + sourceFact.getCitingEid());
            return;
        }

        Optional<ScholardexSourceLink> existingSourceLink = findSourceLink(source, sourceRecordId);
        Optional<ScholardexCitationFact> existingEdge = scholardexCitationFactRepository
                .findByCitedPublicationIdAndCitingPublicationIdAndSource(citedPublicationId, citingPublicationId, source);
        if (existingSourceLink.isPresent()
                && !isBlank(existingSourceLink.get().getCanonicalEntityId())
                && existingEdge.isPresent()
                && !existingSourceLink.get().getCanonicalEntityId().equals(existingEdge.get().getId())) {
            saveConflict(sourceFact, sourceRecordId, REASON_SOURCE_RECORD_COLLISION, List.of(existingEdge.get().getId()));
            result.markSkipped("citation-source-record-collision=" + sourceRecordId);
            return;
        }

        ScholardexCitationFact canonicalFact = existingEdge.orElseGet(ScholardexCitationFact::new);
        Instant now = Instant.now();
        if (canonicalFact.getCreatedAt() == null) {
            canonicalFact.setCreatedAt(now);
        }
        canonicalFact.setId(buildCanonicalCitationId(citedPublicationId, citingPublicationId, source));
        canonicalFact.setCitedPublicationId(citedPublicationId);
        canonicalFact.setCitingPublicationId(citingPublicationId);
        canonicalFact.setSource(source);
        canonicalFact.setSourceRecordId(sourceRecordId);
        canonicalFact.setSourceEventId(sourceFact.getSourceEventId());
        canonicalFact.setSourceBatchId(sourceFact.getSourceBatchId());
        canonicalFact.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        canonicalFact.setUpdatedAt(now);
        scholardexCitationFactRepository.save(canonicalFact);
        upsertSourceLink(canonicalFact, now);

        if (existingEdge.isPresent()) {
            result.markUpdated();
        } else {
            result.markImported();
        }
    }

    private Optional<ScholardexSourceLink> findSourceLink(String source, String sourceRecordId) {
        if (isBlank(source) || isBlank(sourceRecordId)) {
            return Optional.empty();
        }
        Optional<ScholardexSourceLink> sourceLink = scholardexSourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.CITATION, source, sourceRecordId);
        return sourceLink == null ? Optional.empty() : sourceLink;
    }

    private void upsertSourceLink(ScholardexCitationFact citationFact, Instant now) {
        if (isBlank(citationFact.getSource()) || isBlank(citationFact.getSourceRecordId())) {
            return;
        }
        ScholardexSourceLink sourceLink = findSourceLink(citationFact.getSource(), citationFact.getSourceRecordId())
                .orElseGet(ScholardexSourceLink::new);
        sourceLink.setEntityType(ScholardexEntityType.CITATION);
        sourceLink.setSource(citationFact.getSource());
        sourceLink.setSourceRecordId(citationFact.getSourceRecordId());
        sourceLink.setCanonicalEntityId(citationFact.getId());
        sourceLink.setLinkState(LINK_STATE_LINKED);
        sourceLink.setLinkReason(LINK_REASON_SCOPUS_BRIDGE);
        sourceLink.setSourceEventId(citationFact.getSourceEventId());
        sourceLink.setSourceBatchId(citationFact.getSourceBatchId());
        sourceLink.setSourceCorrelationId(citationFact.getSourceCorrelationId());
        if (sourceLink.getLinkedAt() == null) {
            sourceLink.setLinkedAt(now);
        }
        sourceLink.setUpdatedAt(now);
        scholardexSourceLinkRepository.save(sourceLink);
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
}
