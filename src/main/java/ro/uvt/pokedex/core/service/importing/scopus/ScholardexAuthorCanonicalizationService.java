package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final ScholardexAuthorAffiliationFactRepository scholardexAuthorAffiliationFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexEdgeWriterService edgeWriterService;
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

            AuthorChunkOutcome outcome = processChunk(sourceFacts.subList(from, to), result);
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
            log.info("Scholardex author canonicalization chunk {} writes: authorFacts={} sourceLinks[linked={}, unmatched={}, conflict={}, skipped={}] edgeWrites={} conflicts={}",
                    chunkNo,
                    outcome.authorFactWrites(),
                    outcome.sourceLinkLinkedWrites(),
                    outcome.sourceLinkUnmatchedWrites(),
                    outcome.sourceLinkConflictWrites(),
                    outcome.sourceLinkSkippedWrites(),
                    outcome.edgeWrites(),
                    outcome.conflictsWritten());
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
        H19CanonicalMetrics.recordCanonicalBuildRun(
                "author",
                SOURCE_SCOPUS,
                result.getErrorCount() > 0 ? "failure" : "success",
                System.nanoTime() - startedAtNanos
        );
        return result;
    }

    private AuthorChunkOutcome processChunk(List<ScopusAuthorFact> chunk, ImportProcessingResult result) {
        long chunkStartedAtNanos = System.nanoTime();
        ChunkContext context = preloadChunkContext(chunk);
        long preloadFinishedAtNanos = System.nanoTime();
        for (ScopusAuthorFact sourceFact : chunk) {
            result.markProcessed();
            resolveSourceFactIntoContext(sourceFact, result, context);
        }
        long resolveFinishedAtNanos = System.nanoTime();

        int authorFactWrites = 0;
        int sourceLinkLinkedWrites = 0;
        int sourceLinkUnmatchedWrites = 0;
        int sourceLinkConflictWrites = 0;
        int sourceLinkSkippedWrites = 0;
        int edgeWrites = 0;
        int conflictsWritten = context.pendingConflicts.size();

        if (!context.pendingAuthorFacts.isEmpty()) {
            scholardexAuthorFactRepository.saveAll(context.pendingAuthorFacts.values());
            authorFactWrites = context.pendingAuthorFacts.size();
        }
        if (!context.pendingConflicts.isEmpty()) {
            identityConflictRepository.saveAll(context.pendingConflicts.values());
        }

        List<ScholardexSourceLinkService.SourceLinkUpsertCommand> sourceLinkCommands =
                new ArrayList<>(context.pendingSourceLinkCommands.values());
        ScholardexSourceLinkService.BatchWriteResult sourceLinkResults =
                sourceLinkService.batchUpsertWithState(sourceLinkCommands, context.sourceLinkCache, false);
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

        ScholardexEdgeWriterService.BatchEdgeWriteResult edgeResult =
                edgeWriterService.batchUpsertAuthorAffiliationEdges(
                        new ArrayList<>(context.pendingEdgeCommands.values()),
                        context.authorAffiliationEdgeByNaturalKey,
                        context.sourceLinkCache
                );
        edgeWrites = edgeResult.accepted();
        conflictsWritten += edgeResult.conflicts();

        long upsertFinishedAtNanos = System.nanoTime();
        long saveFinishedAtNanos = upsertFinishedAtNanos;
        CanonicalBuildChunkTimings timings = new CanonicalBuildChunkTimings(
                nanosToMillis(preloadFinishedAtNanos - chunkStartedAtNanos),
                nanosToMillis(resolveFinishedAtNanos - preloadFinishedAtNanos),
                nanosToMillis(upsertFinishedAtNanos - resolveFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - upsertFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - chunkStartedAtNanos)
        );
        return new AuthorChunkOutcome(
                timings,
                authorFactWrites,
                sourceLinkLinkedWrites,
                sourceLinkUnmatchedWrites,
                sourceLinkConflictWrites,
                sourceLinkSkippedWrites,
                edgeWrites,
                conflictsWritten
        );
    }

    private ChunkContext preloadChunkContext(List<ScopusAuthorFact> chunk) {
        ChunkContext context = new ChunkContext();
        Set<String> sourceAuthorIds = new LinkedHashSet<>();
        Set<String> sourceAffiliationIds = new LinkedHashSet<>();
        Set<String> sourceAuthorAffiliationEdgeRecordIds = new LinkedHashSet<>();
        Set<String> predictedCanonicalIds = new LinkedHashSet<>();
        for (ScopusAuthorFact sourceFact : chunk) {
            if (sourceFact == null) {
                continue;
            }
            String sourceAuthorId = normalizeBlank(sourceFact.getAuthorId());
            if (sourceAuthorId != null) {
                sourceAuthorIds.add(sourceAuthorId);
                predictedCanonicalIds.add(buildCanonicalAuthorId(sourceAuthorId, sourceFact.getName()));
            }
            if (sourceFact.getAffiliationIds() != null) {
                for (String sourceAffiliationId : sourceFact.getAffiliationIds()) {
                    String normalized = normalizeBlank(sourceAffiliationId);
                    if (normalized != null) {
                        sourceAffiliationIds.add(normalized);
                        if (!isBlank(sourceFact.getSource()) && !isBlank(sourceFact.getSourceRecordId())) {
                            sourceAuthorAffiliationEdgeRecordIds.add(
                                    buildAuthorAffiliationSourceRecordId(sourceFact.getSourceRecordId(), normalized)
                            );
                        }
                        if (!isBlank(sourceFact.getSource()) && !isBlank(sourceFact.getAuthorId())) {
                            sourceAuthorAffiliationEdgeRecordIds.add(
                                    buildAuthorAffiliationSourceRecordId(sourceFact.getAuthorId(), normalized)
                            );
                        }
                    }
                }
            }
        }

        List<ScholardexSourceLink> authorSourceLinks = sourceLinkService
                .findByEntityTypeAndSourceRecordIds(ScholardexEntityType.AUTHOR, sourceAuthorIds);
        for (ScholardexSourceLink link : authorSourceLinks) {
            context.sourceLinkCache.put(toSourceLinkKey(link), link);
            if (!isBlank(link.getCanonicalEntityId())) {
                predictedCanonicalIds.add(link.getCanonicalEntityId());
            }
        }
        List<ScholardexSourceLink> affiliationSourceLinks = sourceLinkService
                .findByEntityTypeAndSourceRecordIds(ScholardexEntityType.AFFILIATION, sourceAffiliationIds);
        for (ScholardexSourceLink link : affiliationSourceLinks) {
            context.sourceLinkCache.put(toSourceLinkKey(link), link);
        }
        List<ScholardexSourceLink> authorAffiliationEdgeSourceLinks = sourceLinkService
                .findByEntityTypeAndSourceRecordIds(ScholardexEntityType.AUTHOR_AFFILIATION, sourceAuthorAffiliationEdgeRecordIds);
        for (ScholardexSourceLink link : authorAffiliationEdgeSourceLinks) {
            context.sourceLinkCache.put(toSourceLinkKey(link), link);
        }

        List<ScholardexAuthorFact> existingBySource = scholardexAuthorFactRepository.findByScopusAuthorIdsIn(sourceAuthorIds);
        for (ScholardexAuthorFact authorFact : existingBySource) {
            context.authorByCanonicalId.put(authorFact.getId(), authorFact);
            for (String sourceId : authorFact.getScopusAuthorIds()) {
                String normalized = normalizeBlank(sourceId);
                if (normalized != null) {
                    context.authorBySourceId.putIfAbsent(normalized, authorFact);
                }
            }
            predictedCanonicalIds.add(authorFact.getId());
        }

        if (!predictedCanonicalIds.isEmpty()) {
            List<ScholardexAuthorFact> existingByCanonical = scholardexAuthorFactRepository.findByIdIn(predictedCanonicalIds);
            for (ScholardexAuthorFact authorFact : existingByCanonical) {
                context.authorByCanonicalId.put(authorFact.getId(), authorFact);
            }
        }

        if (!context.authorByCanonicalId.isEmpty()) {
            List<ScholardexAuthorAffiliationFact> edges = scholardexAuthorAffiliationFactRepository
                    .findByAuthorIdIn(context.authorByCanonicalId.keySet());
            for (ScholardexAuthorAffiliationFact edge : edges) {
                context.authorAffiliationEdgeByNaturalKey.put(edgeNaturalKey(edge.getAuthorId(), edge.getAffiliationId(), edge.getSource()), edge);
            }
        }
        return context;
    }

    private ScholardexSourceLinkService.SourceLinkKey toSourceLinkKey(ScholardexSourceLink link) {
        return ScholardexSourceLinkService.SourceLinkKey.of(link.getEntityType(), link.getSource(), link.getSourceRecordId());
    }

    private void resolveSourceFactIntoContext(ScopusAuthorFact sourceFact, ImportProcessingResult result, ChunkContext context) {
        String sourceRecordId = normalizeBlank(sourceFact == null ? null : sourceFact.getAuthorId());
        if (sourceFact == null || sourceRecordId == null) {
            result.markSkipped("missing scopus author id");
            return;
        }
        String normalizedSource = sourceLinkService.normalizeSource(sourceFact.getSource());

        Optional<ScholardexSourceLink> existingSourceLink = resolveFromChunkSourceLinks(
                context,
                ScholardexEntityType.AUTHOR,
                normalizedSource,
                sourceRecordId
        );
        Optional<ScholardexAuthorFact> existingBySource = Optional.ofNullable(context.authorBySourceId.get(sourceRecordId));
        String canonicalId = existingSourceLink.map(ScholardexSourceLink::getCanonicalEntityId)
                .or(() -> existingBySource.map(ScholardexAuthorFact::getId))
                .orElseGet(() -> buildCanonicalAuthorId(sourceRecordId, sourceFact.getName()));

        if (existingSourceLink.isPresent() && existingSourceLink.get().getCanonicalEntityId() != null
                && !existingSourceLink.get().getCanonicalEntityId().equals(canonicalId)) {
            upsertConflictInContext(
                    context,
                    sourceFact.getSource(),
                    sourceRecordId,
                    sourceFact.getSourceEventId(),
                    sourceFact.getSourceBatchId(),
                    sourceFact.getSourceCorrelationId(),
                    CONFLICT_SOURCE_ID_COLLISION,
                    List.of(existingSourceLink.get().getCanonicalEntityId(), canonicalId)
            );
            result.markSkipped("author-source-id-collision:" + sourceRecordId);
            return;
        }

        AffiliationBridgeResult affiliationBridge = bridgeAffiliationIds(sourceFact.getAffiliationIds(), sourceFact.getSource(), context);
        ScholardexAuthorFact existingTarget = context.authorByCanonicalId.get(canonicalId);
        ScholardexAuthorFact target = existingTarget == null ? new ScholardexAuthorFact() : existingTarget;
        boolean created = existingTarget == null;
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

        context.authorByCanonicalId.put(canonicalId, target);
        context.authorBySourceId.put(sourceRecordId, target);
        context.pendingAuthorFacts.put(canonicalId, target);
        queueSourceLinkCommand(
                context,
                ScholardexEntityType.AUTHOR,
                sourceFact.getSource(),
                sourceRecordId,
                canonicalId,
                ScholardexSourceLinkService.STATE_LINKED,
                LINK_REASON_SCOPUS_BRIDGE,
                sourceFact.getSourceEventId(),
                sourceFact.getSourceBatchId(),
                sourceFact.getSourceCorrelationId()
        );
        queueAuthorAffiliationEdges(context, target, sourceFact, affiliationBridge);

        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    public void upsertFromScopusFact(ScopusAuthorFact sourceFact, ImportProcessingResult result) {
        String sourceRecordId = normalizeBlank(sourceFact == null ? null : sourceFact.getAuthorId());
        if (sourceFact == null || sourceRecordId == null) {
            if (result != null) {
                result.markSkipped("missing scopus author id");
            }
            return;
        }

        Optional<ScholardexSourceLink> existingSourceLink = sourceLinkService
                .findByKey(ScholardexEntityType.AUTHOR, sourceFact.getSource(), sourceRecordId);
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

        ChunkContext bridgeContext = new ChunkContext();
        AffiliationBridgeResult affiliationBridge = bridgeAffiliationIds(sourceFact.getAffiliationIds(), sourceFact.getSource(), bridgeContext);
        if (!bridgeContext.pendingSourceLinkCommands.isEmpty()) {
            sourceLinkService.batchUpsertWithState(
                    bridgeContext.pendingSourceLinkCommands.values(),
                    bridgeContext.sourceLinkCache
            );
        }
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
        upsertAuthorSourceLink(sourceFact, sourceRecordId, target.getId());
        upsertAuthorAffiliationEdges(target, sourceFact, affiliationBridge);

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

    private AffiliationBridgeResult bridgeAffiliationIds(List<String> sourceAffiliationIds, String source, ChunkContext context) {
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
            Optional<ScholardexSourceLink> resolved = resolveAffiliationSourceLink(context, source, sourceAffiliationId);
            if (resolved.isPresent() && !isBlank(resolved.get().getCanonicalEntityId())) {
                String canonicalAffiliationId = resolved.get().getCanonicalEntityId();
                byCanonicalId.putIfAbsent(canonicalAffiliationId, new AffiliationBridgeEntry(canonicalAffiliationId, sourceAffiliationId, false));
                continue;
            }
            String fallbackAffiliationId = buildCanonicalAffiliationFallbackId(sourceToken, sourceAffiliationId);
            byCanonicalId.putIfAbsent(fallbackAffiliationId, new AffiliationBridgeEntry(fallbackAffiliationId, sourceAffiliationId, true));
            pendingSource.add(sourceAffiliationId);
            queueSourceLinkCommand(
                    context,
                    ScholardexEntityType.AFFILIATION,
                    source,
                    sourceAffiliationId,
                    fallbackAffiliationId,
                    ScholardexSourceLinkService.STATE_UNMATCHED,
                    LINK_REASON_AFFILIATION_FALLBACK,
                    null,
                    null,
                    null
            );
        }
        return new AffiliationBridgeResult(
                byCanonicalId.keySet().stream().toList(),
                new ArrayList<>(pendingSource),
                new ArrayList<>(byCanonicalId.values())
        );
    }

    private Optional<ScholardexSourceLink> resolveAffiliationSourceLink(ChunkContext context, String source, String sourceAffiliationId) {
        String normalizedSource = normalizeBlank(source);
        if (normalizedSource != null) {
            Optional<ScholardexSourceLink> direct = resolveFromChunkSourceLinks(
                    context,
                    ScholardexEntityType.AFFILIATION,
                    normalizedSource,
                    sourceAffiliationId
            );
            if (direct.isPresent()) {
                return direct;
            }
        }
        return resolveFromChunkSourceLinks(context, ScholardexEntityType.AFFILIATION, SOURCE_SCOPUS, sourceAffiliationId);
    }

    private Optional<ScholardexSourceLink> resolveFromChunkSourceLinks(
            ChunkContext context,
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId
    ) {
        String normalizedSource = sourceLinkService.normalizeSource(source);
        ScholardexSourceLinkService.SourceLinkKey key =
                ScholardexSourceLinkService.SourceLinkKey.of(entityType, normalizedSource, sourceRecordId);
        ScholardexSourceLink cached = context.sourceLinkCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    private void upsertUnmatchedAffiliationSourceLink(String source, String sourceAffiliationId, String fallbackAffiliationId) {
        if (isBlank(source) || isBlank(sourceAffiliationId) || isBlank(fallbackAffiliationId)) {
            return;
        }
        sourceLinkService.markUnmatched(
                ScholardexEntityType.AFFILIATION,
                source,
                sourceAffiliationId,
                fallbackAffiliationId,
                LINK_REASON_AFFILIATION_FALLBACK,
                null,
                null,
                null,
                false
        );
    }

    private void upsertAuthorSourceLink(ScopusAuthorFact sourceFact, String sourceRecordId, String canonicalId) {
        if (isBlank(sourceFact.getSource())) {
            return;
        }
        sourceLinkService.link(
                ScholardexEntityType.AUTHOR,
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

    private void upsertAuthorAffiliationEdges(
            ScholardexAuthorFact authorFact,
            ScopusAuthorFact sourceFact,
            AffiliationBridgeResult affiliationBridge
    ) {
        for (AffiliationBridgeEntry entry : affiliationBridge.entries()) {
            if (isBlank(sourceFact.getSource())) {
                continue;
            }
            String linkState = entry.pendingResolution()
                    ? ScholardexSourceLinkService.STATE_UNMATCHED
                    : ScholardexSourceLinkService.STATE_LINKED;
            String linkReason = entry.pendingResolution()
                    ? LINK_REASON_AFFILIATION_FALLBACK
                    : LINK_REASON_AUTHOR_AFFILIATION_BRIDGE;
            edgeWriterService.upsertAuthorAffiliationEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                    authorFact.getId(),
                    entry.canonicalAffiliationId(),
                    sourceFact.getSource(),
                    buildAuthorAffiliationSourceRecordId(sourceFact.getSourceRecordId(), entry.sourceAffiliationId()),
                    sourceFact.getSourceEventId(),
                    sourceFact.getSourceBatchId(),
                    sourceFact.getSourceCorrelationId(),
                    linkState,
                    linkReason,
                    false
            ));
        }
    }

    private void queueAuthorAffiliationEdges(
            ChunkContext context,
            ScholardexAuthorFact authorFact,
            ScopusAuthorFact sourceFact,
            AffiliationBridgeResult affiliationBridge
    ) {
        for (AffiliationBridgeEntry entry : affiliationBridge.entries()) {
            if (isBlank(sourceFact.getSource())) {
                continue;
            }
            String linkState = entry.pendingResolution()
                    ? ScholardexSourceLinkService.STATE_UNMATCHED
                    : ScholardexSourceLinkService.STATE_LINKED;
            String linkReason = entry.pendingResolution()
                    ? LINK_REASON_AFFILIATION_FALLBACK
                    : LINK_REASON_AUTHOR_AFFILIATION_BRIDGE;
            ScholardexEdgeWriterService.EdgeWriteCommand command = new ScholardexEdgeWriterService.EdgeWriteCommand(
                    authorFact.getId(),
                    entry.canonicalAffiliationId(),
                    sourceFact.getSource(),
                    buildAuthorAffiliationSourceRecordId(sourceFact.getSourceRecordId(), entry.sourceAffiliationId()),
                    sourceFact.getSourceEventId(),
                    sourceFact.getSourceBatchId(),
                    sourceFact.getSourceCorrelationId(),
                    linkState,
                    linkReason,
                    false
            );
            context.pendingEdgeCommands.put(
                    edgeNaturalKey(command.leftId(), command.rightId(), command.source()),
                    command
            );
        }
    }

    private void queueSourceLinkCommand(
            ChunkContext context,
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalEntityId,
            String targetState,
            String reason,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId
    ) {
        String normalizedSource = sourceLinkService.normalizeSource(source);
        String normalizedRecordId = normalizeBlank(sourceRecordId);
        if (normalizedSource == null || normalizedRecordId == null) {
            return;
        }
        ScholardexSourceLinkService.SourceLinkUpsertCommand command = new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                entityType,
                normalizedSource,
                normalizedRecordId,
                canonicalEntityId,
                targetState,
                reason,
                sourceEventId,
                sourceBatchId,
                sourceCorrelationId,
                false
        );
        String commandKey = entityType.name() + "|" + normalizedSource + "|" + normalizedRecordId;
        context.pendingSourceLinkCommands.put(commandKey, command);
        ScholardexSourceLink synthetic = new ScholardexSourceLink();
        synthetic.setEntityType(entityType);
        synthetic.setSource(normalizedSource);
        synthetic.setSourceRecordId(normalizedRecordId);
        synthetic.setCanonicalEntityId(canonicalEntityId);
        synthetic.setLinkState(targetState);
        synthetic.setLinkReason(reason);
        synthetic.setSourceEventId(sourceEventId);
        synthetic.setSourceBatchId(sourceBatchId);
        synthetic.setSourceCorrelationId(sourceCorrelationId);
        synthetic.setUpdatedAt(Instant.now());
        if (synthetic.getLinkedAt() == null) {
            synthetic.setLinkedAt(Instant.now());
        }
        context.sourceLinkCache.put(toSourceLinkKey(synthetic), synthetic);
    }

    private void upsertConflictInContext(
            ChunkContext context,
            String source,
            String sourceRecordId,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            String reason,
            List<String> candidates
    ) {
        String normalizedSource = normalizeBlank(source);
        String normalizedRecordId = normalizeBlank(sourceRecordId);
        if (normalizedSource == null || normalizedRecordId == null) {
            return;
        }
        String key = ScholardexEntityType.AUTHOR.name() + "|" + normalizedSource + "|" + normalizedRecordId + "|" + reason;
        ScholardexIdentityConflict conflict = context.pendingConflicts.get(key);
        if (conflict == null) {
            conflict = identityConflictRepository
                    .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                            ScholardexEntityType.AUTHOR,
                            normalizedSource,
                            normalizedRecordId,
                            reason,
                            STATUS_OPEN
                    )
                    .orElseGet(ScholardexIdentityConflict::new);
        }
        conflict.setEntityType(ScholardexEntityType.AUTHOR);
        conflict.setIncomingSource(normalizedSource);
        conflict.setIncomingSourceRecordId(normalizedRecordId);
        conflict.setReasonCode(reason);
        conflict.setStatus(STATUS_OPEN);
        conflict.setCandidateCanonicalIds(candidates == null ? List.of() : new ArrayList<>(candidates));
        conflict.setSourceEventId(sourceEventId);
        conflict.setSourceBatchId(sourceBatchId);
        conflict.setSourceCorrelationId(sourceCorrelationId);
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        context.pendingConflicts.put(key, conflict);
        H19CanonicalMetrics.recordConflictCreated(ScholardexEntityType.AUTHOR.name(), normalizedSource, reason);
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
        H19CanonicalMetrics.recordConflictCreated(ScholardexEntityType.AUTHOR.name(), sourceFact.getSource(), reason);
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

    private String edgeNaturalKey(String authorId, String affiliationId, String source) {
        return normalizeToken(authorId) + "|" + normalizeToken(affiliationId) + "|" + normalizeToken(source);
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

    private static class ChunkContext {
        private final Map<String, ScholardexAuthorFact> authorBySourceId = new HashMap<>();
        private final Map<String, ScholardexAuthorFact> authorByCanonicalId = new HashMap<>();
        private final Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> sourceLinkCache = new HashMap<>();
        private final Map<String, ScholardexAuthorFact> pendingAuthorFacts = new LinkedHashMap<>();
        private final Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingSourceLinkCommands = new LinkedHashMap<>();
        private final Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> pendingEdgeCommands = new LinkedHashMap<>();
        private final Map<String, ScholardexIdentityConflict> pendingConflicts = new LinkedHashMap<>();
        private final Map<String, ScholardexAuthorAffiliationFact> authorAffiliationEdgeByNaturalKey = new HashMap<>();
    }

    private record AuthorChunkOutcome(
            CanonicalBuildChunkTimings timings,
            int authorFactWrites,
            int sourceLinkLinkedWrites,
            int sourceLinkUnmatchedWrites,
            int sourceLinkConflictWrites,
            int sourceLinkSkippedWrites,
            int edgeWrites,
            int conflictsWritten
    ) {}
}
