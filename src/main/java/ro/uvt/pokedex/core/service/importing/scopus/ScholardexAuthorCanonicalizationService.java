package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ScholardexAuthorCanonicalizationService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexAuthorCanonicalizationService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1_000;
    private static final String DEFAULT_SOURCE_VERSION = "scopus-author-facts-v1";
    private static final String PIPELINE_KEY = ScholardexCanonicalBuildCheckpointService.AUTHOR_PIPELINE_KEY;
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final String LINK_STATE_LINKED = "LINKED";
    private static final String LINK_STATE_UNMATCHED = "UNMATCHED";
    private static final String STATUS_OPEN = "OPEN";
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-author-bridge";
    private static final String LINK_REASON_AFFILIATION_FALLBACK = "canonical-affiliation-fallback";
    private static final String LINK_REASON_AUTHOR_AFFILIATION_BRIDGE = "author-affiliation-bridge";
    private static final String CONFLICT_SOURCE_ID_COLLISION = "SOURCE_ID_COLLISION";
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final ScopusAuthorFactRepository scopusAuthorFactRepository;
    private final ScholardexAuthorFactRepository scholardexAuthorFactRepository;
    private final ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final ScholardexCanonicalBuildCheckpointService checkpointService;

    public ImportProcessingResult rebuildCanonicalAuthorFactsFromScopusFacts() {
        return rebuildCanonicalAuthorFactsFromScopusFacts(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult rebuildCanonicalAuthorFactsFromScopusFacts(CanonicalBuildOptions options) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScopusAuthorFact> sourceFacts = new ArrayList<>(scopusAuthorFactRepository.findAll());
        sourceFacts.sort(Comparator.comparing(ScopusAuthorFact::getAuthorId, Comparator.nullsLast(String::compareTo)));
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
            log.info("Scholardex author canonicalization skipped: totalRecords={}, totalBatches={}, startBatch={}, checkpointLastCompletedBatch={}",
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

            CanonicalBuildChunkTimings timings = processChunk(sourceFacts.subList(from, to), result);
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
            log.info("Scholardex author canonicalization chunk {} complete [batch={} / totalBatches={}]: records={} imported={} updated={} skipped={} errors={} timingsMs[preload={}, resolve={}, upsert={}, save={}, total={}]",
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

        log.info("Scholardex author canonicalization summary: processed={}, imported={}, updated={}, skipped={}, errors={}, batchesProcessed={}, totalBatches={}, resumedFromCheckpoint={}, checkpointLastCompletedBatch={}, totalMs={}",
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

    private CanonicalBuildChunkTimings processChunk(List<ScopusAuthorFact> chunk, ImportProcessingResult result) {
        long chunkStartedAtNanos = System.nanoTime();
        long preloadFinishedAtNanos = System.nanoTime();
        long resolveFinishedAtNanos = preloadFinishedAtNanos;
        for (ScopusAuthorFact sourceFact : chunk) {
            result.markProcessed();
            upsertFromScopusFact(sourceFact, result);
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

    public void upsertFromScopusFact(ScopusAuthorFact sourceFact, ImportProcessingResult result) {
        String sourceRecordId = normalizeBlank(sourceFact == null ? null : sourceFact.getAuthorId());
        if (sourceFact == null || sourceRecordId == null) {
            if (result != null) {
                result.markSkipped("missing scopus author id");
            }
            return;
        }

        Optional<ScholardexSourceLink> existingSourceLink = sourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.AUTHOR, sourceFact.getSource(), sourceRecordId);
        Optional<ScholardexAuthorFact> existingBySource = scholardexAuthorFactRepository.findByScopusAuthorIdsContains(sourceRecordId);
        String canonicalId = existingSourceLink.map(ScholardexSourceLink::getCanonicalEntityId)
                .or(() -> existingBySource.map(ScholardexAuthorFact::getId))
                .orElseGet(() -> buildCanonicalAuthorId(sourceRecordId, sourceFact.getName()));

        if (existingSourceLink.isPresent() && existingSourceLink.get().getCanonicalEntityId() != null
                && !existingSourceLink.get().getCanonicalEntityId().equals(canonicalId)) {
            saveConflict(sourceFact, sourceRecordId, CONFLICT_SOURCE_ID_COLLISION, List.of(existingSourceLink.get().getCanonicalEntityId(), canonicalId));
            if (result != null) {
                result.markSkipped("author-source-id-collision:" + sourceRecordId);
            }
            return;
        }

        AffiliationBridgeResult affiliationBridge = bridgeAffiliationIds(sourceFact.getAffiliationIds(), sourceFact.getSource());
        ScholardexAuthorFact target = scholardexAuthorFactRepository.findById(canonicalId).orElseGet(ScholardexAuthorFact::new);
        boolean created = target.getId() == null;
        Instant now = Instant.now();
        if (target.getCreatedAt() == null) {
            target.setCreatedAt(now);
        }
        target.setId(canonicalId);
        addUnique(target.getScopusAuthorIds(), sourceRecordId);
        target.setDisplayName(sourceFact.getName());
        target.setNameNormalized(normalizeName(sourceFact.getName()));
        target.setAffiliationIds(affiliationBridge.canonicalAffiliationIds());
        target.setPendingAffiliationSourceIds(affiliationBridge.pendingSourceAffiliationIds());
        target.setSourceEventId(sourceFact.getSourceEventId());
        target.setSource(sourceFact.getSource());
        target.setSourceRecordId(sourceRecordId);
        target.setSourceBatchId(sourceFact.getSourceBatchId());
        target.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        target.setUpdatedAt(now);
        scholardexAuthorFactRepository.save(target);
        upsertAuthorSourceLink(sourceFact, sourceRecordId, target.getId(), now);
        upsertAuthorAffiliationEdges(target, sourceFact, affiliationBridge, now);

        if (result != null) {
            if (created) {
                result.markImported();
            } else {
                result.markUpdated();
            }
        }
    }

    public String buildCanonicalAuthorId(String scopusAuthorId, String displayName) {
        String material;
        if (!isBlank(scopusAuthorId)) {
            material = "scopus|" + normalizeToken(scopusAuthorId);
        } else {
            material = "name|" + normalizeToken(normalizeName(displayName));
        }
        return "sauth_" + shortHash(material);
    }

    private AffiliationBridgeResult bridgeAffiliationIds(List<String> sourceAffiliationIds, String source) {
        if (sourceAffiliationIds == null || sourceAffiliationIds.isEmpty()) {
            return new AffiliationBridgeResult(List.of(), List.of(), List.of());
        }
        String sourceToken = normalizeToken(source);
        Map<String, AffiliationBridgeEntry> byCanonicalId = new LinkedHashMap<>();
        LinkedHashSet<String> pendingSource = new LinkedHashSet<>();
        for (String rawAffiliationId : sourceAffiliationIds) {
            String sourceAffiliationId = normalizeBlank(rawAffiliationId);
            if (sourceAffiliationId == null) {
                continue;
            }
            Optional<ScholardexSourceLink> resolved = resolveAffiliationSourceLink(source, sourceAffiliationId);
            if (resolved.isPresent() && !isBlank(resolved.get().getCanonicalEntityId())) {
                String canonicalAffiliationId = resolved.get().getCanonicalEntityId();
                byCanonicalId.putIfAbsent(canonicalAffiliationId, new AffiliationBridgeEntry(canonicalAffiliationId, sourceAffiliationId, false));
                continue;
            }
            String fallbackAffiliationId = buildCanonicalAffiliationFallbackId(sourceToken, sourceAffiliationId);
            byCanonicalId.putIfAbsent(fallbackAffiliationId, new AffiliationBridgeEntry(fallbackAffiliationId, sourceAffiliationId, true));
            pendingSource.add(sourceAffiliationId);
            upsertUnmatchedAffiliationSourceLink(source, sourceAffiliationId, fallbackAffiliationId);
        }
        return new AffiliationBridgeResult(
                byCanonicalId.keySet().stream().toList(),
                new ArrayList<>(pendingSource),
                new ArrayList<>(byCanonicalId.values())
        );
    }

    private Optional<ScholardexSourceLink> resolveAffiliationSourceLink(String source, String sourceAffiliationId) {
        String normalizedSource = normalizeBlank(source);
        if (normalizedSource != null) {
            Optional<ScholardexSourceLink> direct = sourceLinkRepository
                    .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.AFFILIATION, normalizedSource, sourceAffiliationId);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.AFFILIATION, SOURCE_SCOPUS, sourceAffiliationId);
    }

    private void upsertUnmatchedAffiliationSourceLink(String source, String sourceAffiliationId, String fallbackAffiliationId) {
        if (isBlank(source) || isBlank(sourceAffiliationId) || isBlank(fallbackAffiliationId)) {
            return;
        }
        ScholardexSourceLink sourceLink = sourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.AFFILIATION, source, sourceAffiliationId)
                .orElseGet(ScholardexSourceLink::new);
        Instant now = Instant.now();
        sourceLink.setEntityType(ScholardexEntityType.AFFILIATION);
        sourceLink.setSource(source);
        sourceLink.setSourceRecordId(sourceAffiliationId);
        sourceLink.setCanonicalEntityId(fallbackAffiliationId);
        sourceLink.setLinkState(LINK_STATE_UNMATCHED);
        sourceLink.setLinkReason(LINK_REASON_AFFILIATION_FALLBACK);
        if (sourceLink.getLinkedAt() == null) {
            sourceLink.setLinkedAt(now);
        }
        sourceLink.setUpdatedAt(now);
        sourceLinkRepository.save(sourceLink);
    }

    private void upsertAuthorSourceLink(ScopusAuthorFact sourceFact, String sourceRecordId, String canonicalId, Instant now) {
        if (isBlank(sourceFact.getSource())) {
            return;
        }
        ScholardexSourceLink sourceLink = sourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.AUTHOR, sourceFact.getSource(), sourceRecordId)
                .orElseGet(ScholardexSourceLink::new);
        sourceLink.setEntityType(ScholardexEntityType.AUTHOR);
        sourceLink.setSource(sourceFact.getSource());
        sourceLink.setSourceRecordId(sourceRecordId);
        sourceLink.setCanonicalEntityId(canonicalId);
        sourceLink.setLinkState(LINK_STATE_LINKED);
        sourceLink.setLinkReason(LINK_REASON_SCOPUS_BRIDGE);
        sourceLink.setSourceEventId(sourceFact.getSourceEventId());
        sourceLink.setSourceBatchId(sourceFact.getSourceBatchId());
        sourceLink.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        if (sourceLink.getLinkedAt() == null) {
            sourceLink.setLinkedAt(now);
        }
        sourceLink.setUpdatedAt(now);
        sourceLinkRepository.save(sourceLink);
    }

    private void upsertAuthorAffiliationEdges(
            ScholardexAuthorFact authorFact,
            ScopusAuthorFact sourceFact,
            AffiliationBridgeResult affiliationBridge,
            Instant now
    ) {
        for (AffiliationBridgeEntry entry : affiliationBridge.entries()) {
            ScholardexAuthorAffiliationFact edge = authorAffiliationFactRepository
                    .findByAuthorIdAndAffiliationIdAndSource(authorFact.getId(), entry.canonicalAffiliationId(), sourceFact.getSource())
                    .orElseGet(ScholardexAuthorAffiliationFact::new);
            if (edge.getCreatedAt() == null) {
                edge.setCreatedAt(now);
            }
            edge.setAuthorId(authorFact.getId());
            edge.setAffiliationId(entry.canonicalAffiliationId());
            edge.setSource(sourceFact.getSource());
            edge.setSourceRecordId(buildAuthorAffiliationSourceRecordId(sourceFact.getSourceRecordId(), entry.sourceAffiliationId()));
            edge.setSourceEventId(sourceFact.getSourceEventId());
            edge.setSourceBatchId(sourceFact.getSourceBatchId());
            edge.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
            edge.setLinkState(entry.pendingResolution() ? LINK_STATE_UNMATCHED : LINK_STATE_LINKED);
            edge.setLinkReason(entry.pendingResolution() ? LINK_REASON_AFFILIATION_FALLBACK : LINK_REASON_AUTHOR_AFFILIATION_BRIDGE);
            edge.setUpdatedAt(now);
            authorAffiliationFactRepository.save(edge);
            upsertAuthorAffiliationSourceLink(edge, now);
        }
    }

    private void upsertAuthorAffiliationSourceLink(ScholardexAuthorAffiliationFact edge, Instant now) {
        if (isBlank(edge.getSource()) || isBlank(edge.getSourceRecordId())) {
            return;
        }
        ScholardexSourceLink sourceLink = sourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.AUTHOR_AFFILIATION, edge.getSource(), edge.getSourceRecordId())
                .orElseGet(ScholardexSourceLink::new);
        sourceLink.setEntityType(ScholardexEntityType.AUTHOR_AFFILIATION);
        sourceLink.setSource(edge.getSource());
        sourceLink.setSourceRecordId(edge.getSourceRecordId());
        sourceLink.setCanonicalEntityId(edge.getId());
        sourceLink.setLinkState(edge.getLinkState());
        sourceLink.setLinkReason(edge.getLinkReason());
        sourceLink.setSourceEventId(edge.getSourceEventId());
        sourceLink.setSourceBatchId(edge.getSourceBatchId());
        sourceLink.setSourceCorrelationId(edge.getSourceCorrelationId());
        if (sourceLink.getLinkedAt() == null) {
            sourceLink.setLinkedAt(now);
        }
        sourceLink.setUpdatedAt(now);
        sourceLinkRepository.save(sourceLink);
    }

    private void saveConflict(ScopusAuthorFact sourceFact, String sourceRecordId, String reason, List<String> candidates) {
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        ScholardexEntityType.AUTHOR,
                        sourceFact.getSource(),
                        sourceRecordId,
                        reason,
                        STATUS_OPEN
                )
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(ScholardexEntityType.AUTHOR);
        conflict.setIncomingSource(sourceFact.getSource());
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(reason);
        conflict.setStatus(STATUS_OPEN);
        conflict.setCandidateCanonicalIds(candidates == null ? List.of() : new ArrayList<>(candidates));
        conflict.setSourceEventId(sourceFact.getSourceEventId());
        conflict.setSourceBatchId(sourceFact.getSourceBatchId());
        conflict.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        identityConflictRepository.save(conflict);
    }

    private String buildCanonicalAffiliationFallbackId(String sourceToken, String sourceAffiliationId) {
        String normalizedSource = isBlank(sourceToken) ? "unknown" : sourceToken;
        return "saff_" + shortHash("source|" + normalizedSource + "|affiliation|" + normalizeToken(sourceAffiliationId));
    }

    private String buildAuthorAffiliationSourceRecordId(String sourceAuthorRecordId, String sourceAffiliationId) {
        return normalizeToken(sourceAuthorRecordId) + "::affiliation::" + normalizeToken(sourceAffiliationId);
    }

    private String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void addUnique(List<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!values.contains(value)) {
            values.add(value);
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
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "" : normalized;
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

    private String lastRecordKey(List<ScopusAuthorFact> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return null;
        }
        return normalizeBlank(chunk.get(chunk.size() - 1).getAuthorId());
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    public record AffiliationBridgeResult(
            List<String> canonicalAffiliationIds,
            List<String> pendingSourceAffiliationIds,
            List<AffiliationBridgeEntry> entries
    ) {}

    public record AffiliationBridgeEntry(
            String canonicalAffiliationId,
            String sourceAffiliationId,
            boolean pendingResolution
    ) {}
}
