package ro.uvt.pokedex.core.service.importing.scopus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.addUnique;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.isBlank;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.nanosToMillis;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.normalizeBlank;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.normalizeName;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.normalizeToken;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash;

@Service
public class ScholardexAuthorCanonicalizationService extends AbstractCanonicalizationService<ScopusAuthorFact, ScholardexAuthorCanonicalizationService.ChunkContext> {

    private static final Logger log = LoggerFactory.getLogger(ScholardexAuthorCanonicalizationService.class);
    private static final int DEFAULT_CHUNK_SIZE = 5_000;
    private static final String DEFAULT_SOURCE_VERSION = "scopus-author-facts-v1";
    private static final String PIPELINE_KEY = ScholardexCanonicalBuildCheckpointService.AUTHOR_PIPELINE_KEY;
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-author-bridge";
    private static final String LINK_REASON_AFFILIATION_FALLBACK = "canonical-affiliation-fallback";
    private static final String LINK_REASON_AUTHOR_AFFILIATION_BRIDGE = "author-affiliation-bridge";
    private static final String CONFLICT_SOURCE_ID_COLLISION = "SOURCE_ID_COLLISION";

    private final ScopusAuthorFactRepository scopusAuthorFactRepository;
    private final ScholardexAuthorFactRepository scholardexAuthorFactRepository;
    private final ScholardexAuthorAffiliationFactRepository scholardexAuthorAffiliationFactRepository;
    private final ScholardexEdgeWriterService edgeWriterService;

    @Value("${scopus.canonical.telemetry.heartbeat-seconds:10}")
    private long heartbeatSeconds;

    public ScholardexAuthorCanonicalizationService(
            ScopusAuthorFactRepository scopusAuthorFactRepository,
            ScholardexAuthorFactRepository scholardexAuthorFactRepository,
            ScholardexAuthorAffiliationFactRepository scholardexAuthorAffiliationFactRepository,
            ScholardexEdgeWriterService edgeWriterService,
            ScholardexSourceLinkService sourceLinkService,
            ScholardexIdentityConflictRepository identityConflictRepository,
            ScholardexCanonicalBuildCheckpointService checkpointService,
            ScopusTouchQueueService touchQueueService
    ) {
        super(sourceLinkService, identityConflictRepository, checkpointService, touchQueueService);
        this.scopusAuthorFactRepository = scopusAuthorFactRepository;
        this.scholardexAuthorFactRepository = scholardexAuthorFactRepository;
        this.scholardexAuthorAffiliationFactRepository = scholardexAuthorAffiliationFactRepository;
        this.edgeWriterService = edgeWriterService;
    }

    // ── Public API (unchanged) ──────────────────────────────────────────────

    public ImportProcessingResult rebuildCanonicalAuthorFactsFromScopusFacts() {
        return rebuild(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult rebuildCanonicalAuthorFactsFromScopusFacts(CanonicalBuildOptions options) {
        return rebuild(options);
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

        // For single-record upsert, use a local context for affiliation bridging
        ChunkContext localContext = new ChunkContext();
        AffiliationBridgeResult affiliationBridge = bridgeAffiliationIds(sourceFact.getAffiliationIds(), sourceFact.getSource(), localContext);
        if (!localContext.pendingSourceLinkCommands.isEmpty()) {
            sourceLinkService.batchUpsertWithState(
                    localContext.pendingSourceLinkCommands.values(),
                    localContext.sourceLinkCache
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

    // ── Abstract hook implementations ───────────────────────────────────────

    @Override protected String getPipelineKey() { return PIPELINE_KEY; }
    @Override protected String getEntityTypeLabel() { return "author"; }
    @Override protected ScholardexEntityType getEntityType() { return ScholardexEntityType.AUTHOR; }
    @Override protected int getDefaultChunkSize() { return DEFAULT_CHUNK_SIZE; }
    @Override protected String getDefaultSourceVersion() { return DEFAULT_SOURCE_VERSION; }
    @Override protected long getHeartbeatSeconds() { return heartbeatSeconds; }

    @Override
    protected List<ScopusAuthorFact> loadSourceFacts(CanonicalBuildOptions options) {
        if (!options.fullRescan() && options.incremental()) {
            long startedAtNanos = System.nanoTime();
            log.info("Scholardex author canonicalization source load started: mode=incremental drainQueues={}", options.drainQueues());
            List<String> touchedIds = touchQueueService.consumeAuthorIds(options.drainQueues());
            if (!touchedIds.isEmpty()) {
                List<ScopusAuthorFact> facts = new ArrayList<>(scopusAuthorFactRepository.findByAuthorIdIn(touchedIds));
                log.info("Scholardex author canonicalization source load completed: mode=incremental records={} elapsedMs={}",
                        facts.size(),
                        nanosToMillis(System.nanoTime() - startedAtNanos));
                return facts;
            }
            log.info("Scholardex author canonicalization incremental run has empty touch queue; skipping source scan.");
            return new ArrayList<>();
        }
        long startedAtNanos = System.nanoTime();
        log.info("Scholardex author canonicalization source load started: mode=full-rescan");
        List<ScopusAuthorFact> facts = new ArrayList<>(scopusAuthorFactRepository.findAll());
        log.info("Scholardex author canonicalization source load completed: mode=full-rescan records={} elapsedMs={}",
                facts.size(),
                nanosToMillis(System.nanoTime() - startedAtNanos));
        return facts;
    }

    @Override
    protected void sortSourceFacts(List<ScopusAuthorFact> facts) {
        facts.sort(Comparator.comparing(ScopusAuthorFact::getAuthorId, Comparator.nullsLast(String::compareTo)));
    }

    @Override
    protected String lastRecordKey(List<ScopusAuthorFact> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return null;
        }
        return normalizeBlank(chunk.getLast().getAuthorId());
    }

    @Override
    protected ChunkContext createChunkContext() {
        return new ChunkContext();
    }

    @Override
    protected void preloadChunkContext(List<ScopusAuthorFact> chunk, ChunkContext context) {
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
    }

    @Override
    protected void processSourceFact(ScopusAuthorFact sourceFact, ImportProcessingResult result, ChunkContext context) {
        String sourceRecordId = normalizeBlank(sourceFact == null ? null : sourceFact.getAuthorId());
        if (sourceFact == null || sourceRecordId == null) {
            result.markSkipped("missing scopus author id");
            return;
        }
        String normalizedSource = sourceLinkService.normalizeSource(sourceFact.getSource());

        Optional<ScholardexSourceLink> existingSourceLink = resolveFromChunkSourceLinks(
                ScholardexEntityType.AUTHOR,
                normalizedSource,
                sourceRecordId,
                context
        );
        Optional<ScholardexAuthorFact> existingBySource = Optional.ofNullable(context.authorBySourceId.get(sourceRecordId));
        String canonicalId = existingSourceLink.map(ScholardexSourceLink::getCanonicalEntityId)
                .or(() -> existingBySource.map(ScholardexAuthorFact::getId))
                .orElseGet(() -> buildCanonicalAuthorId(sourceRecordId, sourceFact.getName()));

        if (existingSourceLink.isPresent() && existingSourceLink.get().getCanonicalEntityId() != null
                && !existingSourceLink.get().getCanonicalEntityId().equals(canonicalId)) {
            upsertConflictInContext(
                    sourceFact.getSource(),
                    sourceRecordId,
                    sourceFact.getSourceEventId(),
                    sourceFact.getSourceBatchId(),
                    sourceFact.getSourceCorrelationId(),
                    CONFLICT_SOURCE_ID_COLLISION,
                    List.of(existingSourceLink.get().getCanonicalEntityId(), canonicalId),
                    context
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
                ScholardexEntityType.AUTHOR,
                sourceFact.getSource(),
                sourceRecordId,
                canonicalId,
                ScholardexSourceLinkService.STATE_LINKED,
                LINK_REASON_SCOPUS_BRIDGE,
                sourceFact.getSourceEventId(),
                sourceFact.getSourceBatchId(),
                sourceFact.getSourceCorrelationId(),
                context
        );
        queueAuthorAffiliationEdges(target, sourceFact, affiliationBridge, context);

        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    @Override
    protected CanonicalBuildChunkTimings flushPendingWrites(long chunkStartedAtNanos, long preloadFinishedAtNanos, long resolveFinishedAtNanos, ChunkContext context) {
        context.lastConflictsWritten = context.pendingConflicts.size();

        if (!context.pendingAuthorFacts.isEmpty()) {
            scholardexAuthorFactRepository.saveAll(context.pendingAuthorFacts.values());
            context.lastAuthorFactWrites = context.pendingAuthorFacts.size();
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
                context.lastSourceLinkLinkedWrites++;
            } else if (ScholardexSourceLinkService.STATE_UNMATCHED.equals(state)) {
                context.lastSourceLinkUnmatchedWrites++;
            } else if (ScholardexSourceLinkService.STATE_CONFLICT.equals(state)) {
                context.lastSourceLinkConflictWrites++;
            } else if (ScholardexSourceLinkService.STATE_SKIPPED.equals(state)) {
                context.lastSourceLinkSkippedWrites++;
            }
        }

        ScholardexEdgeWriterService.BatchEdgeWriteResult edgeResult =
                edgeWriterService.batchUpsertAuthorAffiliationEdges(
                        new ArrayList<>(context.pendingEdgeCommands.values()),
                        context.authorAffiliationEdgeByNaturalKey,
                        context.sourceLinkCache,
                        false
                );
        context.lastEdgeWrites = edgeResult.accepted();
        context.lastConflictsWritten += edgeResult.conflicts();

        long upsertFinishedAtNanos = System.nanoTime();
        return new CanonicalBuildChunkTimings(
                nanosToMillis(preloadFinishedAtNanos - chunkStartedAtNanos),
                nanosToMillis(resolveFinishedAtNanos - preloadFinishedAtNanos),
                nanosToMillis(upsertFinishedAtNanos - resolveFinishedAtNanos),
                0L,
                nanosToMillis(upsertFinishedAtNanos - chunkStartedAtNanos)
        );
    }

    @Override
    protected void afterChunkLogged(int chunkNo, ChunkContext context) {
        log.info("Scholardex author canonicalization chunk {} writes: authorFacts={} sourceLinks[linked={}, unmatched={}, conflict={}, skipped={}] edgeWrites={} conflicts={}",
                chunkNo,
                context.lastAuthorFactWrites,
                context.lastSourceLinkLinkedWrites,
                context.lastSourceLinkUnmatchedWrites,
                context.lastSourceLinkConflictWrites,
                context.lastSourceLinkSkippedWrites,
                context.lastEdgeWrites,
                context.lastConflictsWritten);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

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
            Optional<ScholardexSourceLink> resolved = resolveAffiliationSourceLink(source, sourceAffiliationId, context);
            if (resolved.isPresent() && !isBlank(resolved.get().getCanonicalEntityId())) {
                String canonicalAffiliationId = resolved.get().getCanonicalEntityId();
                byCanonicalId.putIfAbsent(canonicalAffiliationId, new AffiliationBridgeEntry(canonicalAffiliationId, sourceAffiliationId, false));
                continue;
            }
            String fallbackAffiliationId = buildCanonicalAffiliationFallbackId(sourceToken, sourceAffiliationId);
            byCanonicalId.putIfAbsent(fallbackAffiliationId, new AffiliationBridgeEntry(fallbackAffiliationId, sourceAffiliationId, true));
            pendingSource.add(sourceAffiliationId);
            queueSourceLinkCommand(
                    ScholardexEntityType.AFFILIATION,
                    source,
                    sourceAffiliationId,
                    fallbackAffiliationId,
                    ScholardexSourceLinkService.STATE_UNMATCHED,
                    LINK_REASON_AFFILIATION_FALLBACK,
                    null,
                    null,
                    null,
                    context
            );
        }
        return new AffiliationBridgeResult(
                byCanonicalId.keySet().stream().toList(),
                new ArrayList<>(pendingSource),
                new ArrayList<>(byCanonicalId.values())
        );
    }

    private Optional<ScholardexSourceLink> resolveAffiliationSourceLink(String source, String sourceAffiliationId, ChunkContext context) {
        String normalizedSource = normalizeBlank(source);
        if (normalizedSource != null) {
            Optional<ScholardexSourceLink> direct = resolveFromChunkSourceLinks(
                    ScholardexEntityType.AFFILIATION,
                    normalizedSource,
                    sourceAffiliationId,
                    context
            );
            if (direct.isPresent()) {
                return direct;
            }
        }
        return resolveFromChunkSourceLinks(ScholardexEntityType.AFFILIATION, SOURCE_SCOPUS, sourceAffiliationId, context);
    }

    private Optional<ScholardexSourceLink> resolveFromChunkSourceLinks(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            ChunkContext context
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

    private ScholardexSourceLinkService.SourceLinkKey toSourceLinkKey(ScholardexSourceLink link) {
        return ScholardexSourceLinkService.SourceLinkKey.of(link.getEntityType(), link.getSource(), link.getSourceRecordId());
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
                    buildAuthorAffiliationSourceRecordId(sourceFact.getAuthorId(), entry.sourceAffiliationId()),
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
            ScholardexAuthorFact authorFact,
            ScopusAuthorFact sourceFact,
            AffiliationBridgeResult affiliationBridge,
            ChunkContext context
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
                    buildAuthorAffiliationSourceRecordId(sourceFact.getAuthorId(), entry.sourceAffiliationId()),
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
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalEntityId,
            String targetState,
            String reason,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            ChunkContext context
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
            String source,
            String sourceRecordId,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            String reason,
            List<String> candidates,
            ChunkContext context
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
                            CanonicalizationSupport.STATUS_OPEN
                    )
                    .orElseGet(ScholardexIdentityConflict::new);
        }
        conflict.setEntityType(ScholardexEntityType.AUTHOR);
        conflict.setIncomingSource(normalizedSource);
        conflict.setIncomingSourceRecordId(normalizedRecordId);
        conflict.setReasonCode(reason);
        conflict.setStatus(CanonicalizationSupport.STATUS_OPEN);
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
                        CanonicalizationSupport.STATUS_OPEN
                )
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(ScholardexEntityType.AUTHOR);
        conflict.setIncomingSource(sourceFact.getSource());
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(reason);
        conflict.setStatus(CanonicalizationSupport.STATUS_OPEN);
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

    private String edgeNaturalKey(String authorId, String affiliationId, String source) {
        return normalizeToken(authorId) + "|" + normalizeToken(affiliationId) + "|" + normalizeToken(source);
    }

    // ── Records and chunk context ───────────────────────────────────────────

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

    static class ChunkContext {
        private final Map<String, ScholardexAuthorFact> authorBySourceId = new HashMap<>();
        private final Map<String, ScholardexAuthorFact> authorByCanonicalId = new HashMap<>();
        private final Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> sourceLinkCache = new HashMap<>();
        private final Map<String, ScholardexAuthorFact> pendingAuthorFacts = new LinkedHashMap<>();
        private final Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingSourceLinkCommands = new LinkedHashMap<>();
        private final Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> pendingEdgeCommands = new LinkedHashMap<>();
        private final Map<String, ScholardexIdentityConflict> pendingConflicts = new LinkedHashMap<>();
        private final Map<String, ScholardexAuthorAffiliationFact> authorAffiliationEdgeByNaturalKey = new HashMap<>();

        // Per-chunk write statistics (set by flushPendingWrites, read by afterChunkLogged)
        private int lastAuthorFactWrites;
        private int lastSourceLinkLinkedWrites;
        private int lastSourceLinkUnmatchedWrites;
        private int lastSourceLinkConflictWrites;
        private int lastSourceLinkSkippedWrites;
        private int lastEdgeWrites;
        private int lastConflictsWritten;
    }
}
