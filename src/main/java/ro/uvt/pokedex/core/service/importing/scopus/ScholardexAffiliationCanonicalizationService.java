package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ScholardexAffiliationCanonicalizationService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexAffiliationCanonicalizationService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1_000;
    private static final String DEFAULT_SOURCE_VERSION = "scopus-affiliation-facts-v1";
    private static final String PIPELINE_KEY = ScholardexCanonicalBuildCheckpointService.AFFILIATION_PIPELINE_KEY;
    private static final String STATUS_OPEN = "OPEN";
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-affiliation-bridge";
    private static final String CONFLICT_SOURCE_ID_COLLISION = "SOURCE_ID_COLLISION";
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final ScopusAffiliationFactRepository scopusAffiliationFactRepository;
    private final ScholardexAffiliationFactRepository scholardexAffiliationFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final ScholardexCanonicalBuildCheckpointService checkpointService;

    public ImportProcessingResult rebuildCanonicalAffiliationFactsFromScopusFacts() {
        return rebuildCanonicalAffiliationFactsFromScopusFacts(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult rebuildCanonicalAffiliationFactsFromScopusFacts(CanonicalBuildOptions options) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScopusAffiliationFact> sourceFacts = new ArrayList<>(scopusAffiliationFactRepository.findAll());
        sourceFacts.sort(Comparator.comparing(ScopusAffiliationFact::getAfid, Comparator.nullsLast(String::compareTo)));
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
            log.info("Scholardex affiliation canonicalization skipped: totalRecords={}, totalBatches={}, startBatch={}, checkpointLastCompletedBatch={}",
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
            log.info("Scholardex affiliation canonicalization chunk {} complete [batch={} / totalBatches={}]: records={} imported={} updated={} skipped={} errors={} timingsMs[preload={}, resolve={}, upsert={}, save={}, total={}]",
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
        log.info("Scholardex affiliation canonicalization summary: processed={}, imported={}, updated={}, skipped={}, errors={}, batchesProcessed={}, totalBatches={}, resumedFromCheckpoint={}, checkpointLastCompletedBatch={}, totalMs={}",
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
                "affiliation",
                "SCOPUS",
                result.getErrorCount() > 0 ? "failure" : "success",
                System.nanoTime() - startedAtNanos
        );
        return result;
    }

    private CanonicalBuildChunkTimings processChunk(List<ScopusAffiliationFact> chunk, ImportProcessingResult result) {
        long chunkStartedAtNanos = System.nanoTime();
        long preloadFinishedAtNanos = System.nanoTime();
        long resolveFinishedAtNanos = preloadFinishedAtNanos;
        for (ScopusAffiliationFact sourceFact : chunk) {
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

    public void upsertFromScopusFact(ScopusAffiliationFact sourceFact, ImportProcessingResult result) {
        String sourceRecordId = normalizeBlank(sourceFact == null ? null : sourceFact.getAfid());
        if (sourceFact == null || sourceRecordId == null) {
            if (result != null) {
                result.markSkipped("missing scopus affiliation id");
            }
            return;
        }

        Optional<ScholardexSourceLink> existingSourceLink = sourceLinkService
                .findByKey(ScholardexEntityType.AFFILIATION, sourceFact.getSource(), sourceRecordId);
        Optional<ScholardexAffiliationFact> existingBySource = scholardexAffiliationFactRepository.findByScopusAffiliationIdsContains(sourceRecordId);
        String canonicalId = existingSourceLink.map(ScholardexSourceLink::getCanonicalEntityId)
                .or(() -> existingBySource.map(ScholardexAffiliationFact::getId))
                .orElseGet(() -> buildCanonicalAffiliationId(sourceRecordId, sourceFact.getName(), sourceFact.getCity(), sourceFact.getCountry()));

        if (existingSourceLink.isPresent() && existingSourceLink.get().getCanonicalEntityId() != null
                && !existingSourceLink.get().getCanonicalEntityId().equals(canonicalId)) {
            saveConflict(sourceFact, sourceRecordId, CONFLICT_SOURCE_ID_COLLISION, List.of(existingSourceLink.get().getCanonicalEntityId(), canonicalId));
            if (result != null) {
                result.markSkipped("affiliation-source-id-collision:" + sourceRecordId);
            }
            return;
        }

        ScholardexAffiliationFact target = scholardexAffiliationFactRepository.findById(canonicalId).orElseGet(ScholardexAffiliationFact::new);
        boolean created = target.getId() == null;
        Instant now = Instant.now();
        if (target.getCreatedAt() == null) {
            target.setCreatedAt(now);
        }
        target.setId(canonicalId);
        addUnique(target.getScopusAffiliationIds(), sourceRecordId);
        target.setName(sourceFact.getName());
        target.setNameNormalized(normalizeName(sourceFact.getName()));
        target.setCity(sourceFact.getCity());
        target.setCountry(sourceFact.getCountry());
        addUnique(target.getAliases(), normalizeAlias(sourceFact.getName(), sourceFact.getCity(), sourceFact.getCountry()));
        target.setSourceEventId(sourceFact.getSourceEventId());
        target.setSource(sourceFact.getSource());
        target.setSourceRecordId(sourceRecordId);
        target.setSourceBatchId(sourceFact.getSourceBatchId());
        target.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        target.setUpdatedAt(now);
        scholardexAffiliationFactRepository.save(target);
        upsertSourceLink(sourceFact, sourceRecordId, target.getId(), now);

        if (result != null) {
            if (created) {
                result.markImported();
            } else {
                result.markUpdated();
            }
        }
    }

    public String buildCanonicalAffiliationId(String scopusAffiliationId, String name, String city, String country) {
        String material;
        if (!isBlank(scopusAffiliationId)) {
            material = "scopus|" + normalizeToken(scopusAffiliationId);
        } else {
            material = "name|" + normalizeToken(normalizeName(name))
                    + "|city|" + normalizeToken(city)
                    + "|country|" + normalizeToken(country);
        }
        return "saff_" + shortHash(material);
    }

    private void upsertSourceLink(ScopusAffiliationFact sourceFact, String sourceRecordId, String canonicalId, Instant now) {
        if (isBlank(sourceFact.getSource())) {
            return;
        }
        sourceLinkService.link(
                ScholardexEntityType.AFFILIATION,
                sourceFact.getSource(),
                sourceRecordId,
                canonicalId,
                LINK_REASON_SCOPUS_BRIDGE,
                sourceFact.getSourceEventId(),
                sourceFact.getSourceBatchId(),
                sourceFact.getSourceCorrelationId(),
                false
        );
    }

    private void saveConflict(ScopusAffiliationFact sourceFact, String sourceRecordId, String reason, List<String> candidates) {
        ScholardexIdentityConflict conflict = identityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        ScholardexEntityType.AFFILIATION,
                        sourceFact.getSource(),
                        sourceRecordId,
                        reason,
                        STATUS_OPEN
                )
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(ScholardexEntityType.AFFILIATION);
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
        H19CanonicalMetrics.recordConflictCreated(ScholardexEntityType.AFFILIATION.name(), sourceFact.getSource(), reason);
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

    private String normalizeAlias(String name, String city, String country) {
        String alias = normalizeToken(normalizeName(name)) + "|" + normalizeToken(city) + "|" + normalizeToken(country);
        return alias.equals("||") ? null : alias;
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

    private String lastRecordKey(List<ScopusAffiliationFact> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return null;
        }
        return normalizeBlank(chunk.get(chunk.size() - 1).getAfid());
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
