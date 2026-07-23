package ro.uvt.pokedex.core.service.importing.scopus;

import ro.uvt.pokedex.core.service.importing.BuilderVersion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.observability.CanonicalObservabilityMetrics;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.isBlank;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.nanosToMillis;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.normalizeBlank;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.normalizeToken;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash;

@Service
public class ScholardexCitationCanonicalizationService extends AbstractCanonicalizationService<ScopusCitationFact, ScholardexCitationCanonicalizationService.ChunkContext> {

    private static final Logger log = LoggerFactory.getLogger(ScholardexCitationCanonicalizationService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1_000;
    private static final String DEFAULT_SOURCE_VERSION = "scopus-citation-facts-v1";
    private static final String PIPELINE_KEY = ScholardexCanonicalBuildCheckpointService.CITATION_PIPELINE_KEY;
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-citation-bridge";
    private static final String REASON_UNRESOLVED_CITED = "UNRESOLVED_CITED_PUBLICATION";
    private static final String REASON_UNRESOLVED_CITING = "UNRESOLVED_CITING_PUBLICATION";
    private static final String REASON_SOURCE_RECORD_COLLISION = "CITATION_SOURCE_RECORD_COLLISION";

    private final ScopusCitationFactRepository scopusCitationFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexCitationFactRepository scholardexCitationFactRepository;

    @Value("${scopus.canonical.telemetry.heartbeat-seconds:10}")
    private long heartbeatSeconds;
    @Value("${scopus.canonical.telemetry.load-progress-record-interval:10000}")
    private int loadProgressRecordInterval;

    public ScholardexCitationCanonicalizationService(
            ScopusCitationFactRepository scopusCitationFactRepository,
            ScholardexPublicationFactRepository scholardexPublicationFactRepository,
            ScholardexCitationFactRepository scholardexCitationFactRepository,
            ScholardexSourceLinkService sourceLinkService,
            ScholardexIdentityConflictRepository identityConflictRepository,
            ScholardexCanonicalBuildCheckpointService checkpointService
    ) {
        super(sourceLinkService, identityConflictRepository, checkpointService);
        this.scopusCitationFactRepository = scopusCitationFactRepository;
        this.scholardexPublicationFactRepository = scholardexPublicationFactRepository;
        this.scholardexCitationFactRepository = scholardexCitationFactRepository;
    }

    // ── Public API (unchanged) ──────────────────────────────────────────────

    public ImportProcessingResult rebuildCanonicalCitationFactsFromScopusFacts() {
        return rebuild(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult rebuildCanonicalCitationFactsFromScopusFacts(CanonicalBuildOptions options) {
        return rebuild(options);
    }

    // ── Abstract hook implementations ───────────────────────────────────────

    @Override protected String getPipelineKey() { return PIPELINE_KEY; }
    @Override protected String getEntityTypeLabel() { return "citation"; }
    @Override protected ScholardexEntityType getEntityType() { return ScholardexEntityType.CITATION; }
    @Override protected int getDefaultChunkSize() { return DEFAULT_CHUNK_SIZE; }
    @Override protected String getDefaultSourceVersion() { return DEFAULT_SOURCE_VERSION; }
    @Override protected long getHeartbeatSeconds() { return heartbeatSeconds; }

    @Override
    protected List<ScopusCitationFact> loadSourceFacts(CanonicalBuildOptions options) {
        long startedAtNanos = System.nanoTime();
        String sourceBatchIdFilter = options == null ? null : options.sourceBatchIdFilter();
        if (!isBlank(sourceBatchIdFilter)) {
            log.info("Scholardex citation canonicalization source load started: mode=batch-filter sourceBatchIdFilter={}", sourceBatchIdFilter);
            List<ScopusCitationFact> facts = new ArrayList<>(scopusCitationFactRepository.findBySourceBatchId(sourceBatchIdFilter));
            log.info("Scholardex citation canonicalization source load completed: mode=batch-filter sourceBatchIdFilter={} records={} elapsedMs={}",
                    sourceBatchIdFilter,
                    facts.size(),
                    nanosToMillis(System.nanoTime() - startedAtNanos));
            return facts;
        }
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

    @Override
    protected void sortSourceFacts(List<ScopusCitationFact> facts) {
        facts.sort(Comparator
                .comparing(ScopusCitationFact::getCitedEid, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusCitationFact::getCitingEid, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusCitationFact::getSourceRecordId, Comparator.nullsLast(String::compareTo)));
    }

    @Override
    protected String lastRecordKey(List<ScopusCitationFact> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return null;
        }
        ScopusCitationFact last = chunk.getLast();
        String sourceRecordId = normalizeBlank(last.getSourceRecordId());
        if (sourceRecordId != null) {
            return sourceRecordId;
        }
        return normalizeBlank(last.getCitedEid()) + "->" + normalizeBlank(last.getCitingEid());
    }

    @Override
    protected ChunkContext createChunkContext() {
        return new ChunkContext();
    }

    @Override
    protected void preloadChunkContext(List<ScopusCitationFact> chunk, ChunkContext context) {
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
            String citedPublicationId = context.publicationIdByEid.get(sourceFact.getCitedEid());
            String citingPublicationId = context.publicationIdByEid.get(sourceFact.getCitingEid());
            if (!isBlank(citedPublicationId) && !isBlank(citingPublicationId)) {
                edgeIds.add(buildCanonicalCitationId(citedPublicationId, citingPublicationId));
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
    }

    @Override
    protected void processSourceFact(ScopusCitationFact sourceFact, ImportProcessingResult result, ChunkContext context) {
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
            queueConflict(sourceFact, sourceRecordId, REASON_UNRESOLVED_CITED, List.of(), context);
            result.markSkipped("unresolved-cited-eid=" + sourceFact.getCitedEid());
            return;
        }
        String citingPublicationId = context.publicationIdByEid.get(sourceFact.getCitingEid());
        if (isBlank(citingPublicationId)) {
            queueConflict(sourceFact, sourceRecordId, REASON_UNRESOLVED_CITING, List.of(citedPublicationId), context);
            result.markSkipped("unresolved-citing-eid=" + sourceFact.getCitingEid());
            return;
        }

        Optional<ScholardexSourceLink> existingSourceLink = resolveFromChunkSourceLinks(source, sourceRecordId, context);
        String edgeId = buildCanonicalCitationId(citedPublicationId, citingPublicationId);
        ScholardexCitationFact existingEdge = context.citationById.get(edgeId);
        if (existingSourceLink.isPresent()
                && !isBlank(existingSourceLink.get().getCanonicalEntityId())
                && existingEdge != null
                && !existingSourceLink.get().getCanonicalEntityId().equals(existingEdge.getId())) {
            queueConflict(sourceFact, sourceRecordId, REASON_SOURCE_RECORD_COLLISION, List.of(existingEdge.getId()), context);
            result.markSkipped("citation-source-record-collision=" + sourceRecordId);
            return;
        }

        if (existingEdge != null) {
            queueSourceLinkCommand(sourceFact, sourceRecordId, existingEdge.getId(), context);
            result.markSkipped("citation-edge-already-known=" + edgeId);
            return;
        }

        ScholardexCitationFact canonicalFact = new ScholardexCitationFact();
        Instant now = Instant.now();
        canonicalFact.setCreatedAt(now);
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
        queueSourceLinkCommand(sourceFact, sourceRecordId, canonicalFact.getId(), context);
        result.markImported();
    }

    @Override
    protected CanonicalBuildChunkTimings flushPendingWrites(long chunkStartedAtNanos, long preloadFinishedAtNanos, long resolveFinishedAtNanos, ChunkContext context) {
        if (!context.pendingCitationFacts.isEmpty()) {
            context.pendingCitationFacts.values().forEach(f -> f.setBuilderVersion(BuilderVersion.SCHOLARDEX_CITATION));
            context.lastCitationFactWrites = savePendingCitationFactsTolerantly(context.pendingCitationFacts.values());
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
                    context.lastSourceLinkLinkedWrites++;
                } else if (ScholardexSourceLinkService.STATE_UNMATCHED.equals(state)) {
                    context.lastSourceLinkUnmatchedWrites++;
                } else if (ScholardexSourceLinkService.STATE_CONFLICT.equals(state)) {
                    context.lastSourceLinkConflictWrites++;
                } else if (ScholardexSourceLinkService.STATE_SKIPPED.equals(state)) {
                    context.lastSourceLinkSkippedWrites++;
                }
            }
        }
        if (!context.pendingConflicts.isEmpty()) {
            identityConflictRepository.saveAll(context.pendingConflicts.values());
            context.lastConflictsWritten = context.pendingConflicts.size();
        }
        long saveFinishedAtNanos = System.nanoTime();

        return new CanonicalBuildChunkTimings(
                nanosToMillis(preloadFinishedAtNanos - chunkStartedAtNanos),
                nanosToMillis(resolveFinishedAtNanos - preloadFinishedAtNanos),
                0L,
                nanosToMillis(saveFinishedAtNanos - resolveFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - chunkStartedAtNanos)
        );
    }

    @Override
    protected void afterChunkLogged(int chunkNo, ChunkContext context) {
        log.info("Scholardex citation canonicalization chunk {} writes: citationFacts={} sourceLinks[linked={}, unmatched={}, conflict={}, skipped={}] conflicts={}",
                chunkNo,
                context.lastCitationFactWrites,
                context.lastSourceLinkLinkedWrites,
                context.lastSourceLinkUnmatchedWrites,
                context.lastSourceLinkConflictWrites,
                context.lastSourceLinkSkippedWrites,
                context.lastConflictsWritten);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * The preload's already-known check reads a snapshot; a concurrent writer (an OpenAlex author
     * sync, or another Scopus run touching the same pair) can insert the same (cited, citing) edge
     * — possibly under a differently-minted id — between that read and this save, so the unique
     * pair index can still fire. Mirrors {@code OpenAlexCitationCanonicalizationService.writeCitation}'s
     * DuplicateKeyException→skip guard: the edge existing is the DESIRED end state, not an error, and
     * without this the whole citations-update task hard-fails (non-retryably) on one known edge.
     * Bulk saveAll stays the fast path; only a collision downgrades to per-document saves so the
     * one duplicate is skipped without dropping its siblings.
     */
    private int savePendingCitationFactsTolerantly(java.util.Collection<ScholardexCitationFact> facts) {
        try {
            scholardexCitationFactRepository.saveAll(facts);
            return facts.size();
        } catch (org.springframework.dao.DuplicateKeyException bulkCollision) {
            int written = 0;
            for (ScholardexCitationFact fact : facts) {
                try {
                    scholardexCitationFactRepository.save(fact);
                    written++;
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    log.info("Scholardex citation canonicalization: edge already present (cited={}, citing={}) — skipping",
                            fact.getCitedPublicationId(), fact.getCitingPublicationId());
                }
            }
            return written;
        }
    }

    private String buildCanonicalCitationId(String citedPublicationId, String citingPublicationId) {
        return "scit_" + shortHash(
                normalizeBlank(citedPublicationId) + "|" + normalizeBlank(citingPublicationId)
        );
    }

    private Optional<ScholardexSourceLink> resolveFromChunkSourceLinks(String source, String sourceRecordId, ChunkContext context) {
        if (isBlank(source) || isBlank(sourceRecordId)) {
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

    private void queueSourceLinkCommand(
            ScopusCitationFact sourceFact,
            String sourceRecordId,
            String canonicalCitationId,
            ChunkContext context
    ) {
        String source = normalizeBlank(sourceFact.getSource());
        if (isBlank(source) || isBlank(sourceRecordId) || isBlank(canonicalCitationId)) {
            return;
        }
        String key = normalizeToken(source) + "|" + normalizeToken(sourceRecordId);
        context.pendingSourceLinkCommands.put(key, new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                ScholardexEntityType.CITATION,
                source,
                sourceRecordId,
                canonicalCitationId,
                ScholardexSourceLinkService.STATE_LINKED,
                LINK_REASON_SCOPUS_BRIDGE,
                sourceFact.getSourceEventId(),
                sourceFact.getSourceBatchId(),
                sourceFact.getSourceCorrelationId(),
                false
        ));
    }

    private void queueConflict(
            ScopusCitationFact sourceFact,
            String sourceRecordId,
            String reasonCode,
            List<String> candidates,
            ChunkContext context
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
            conflict = identityConflictRepository
                    .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                            ScholardexEntityType.CITATION,
                            incomingSource,
                            sourceRecordId,
                            reasonCode,
                            CanonicalizationSupport.STATUS_OPEN
                    )
                    .orElseGet(ScholardexIdentityConflict::new);
        }
        conflict.setEntityType(ScholardexEntityType.CITATION);
        conflict.setIncomingSource(incomingSource);
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(reasonCode);
        conflict.setStatus(CanonicalizationSupport.STATUS_OPEN);
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        conflict.setCandidateCanonicalIds(candidates == null ? List.of() : new ArrayList<>(candidates));
        conflict.setSourceEventId(sourceFact.getSourceEventId());
        conflict.setSourceBatchId(sourceFact.getSourceBatchId());
        conflict.setSourceCorrelationId(sourceFact.getSourceCorrelationId());
        context.pendingConflicts.put(key, conflict);
        CanonicalObservabilityMetrics.recordConflictCreated(ScholardexEntityType.CITATION.name(), incomingSource, reasonCode);
    }

    // ── Chunk context ───────────────────────────────────────────────────────

    static class ChunkContext {
        private final Map<String, String> publicationIdByEid = new LinkedHashMap<>();
        private final Map<String, ScholardexCitationFact> citationById = new LinkedHashMap<>();
        private final Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> sourceLinkCache = new LinkedHashMap<>();
        private final Map<String, ScholardexCitationFact> pendingCitationFacts = new LinkedHashMap<>();
        private final Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingSourceLinkCommands = new LinkedHashMap<>();
        private final Map<String, ScholardexIdentityConflict> pendingConflicts = new LinkedHashMap<>();

        // Per-chunk write statistics (set by flushPendingWrites, read by afterChunkLogged)
        private int lastCitationFactWrites;
        private int lastSourceLinkLinkedWrites;
        private int lastSourceLinkUnmatchedWrites;
        private int lastSourceLinkConflictWrites;
        private int lastSourceLinkSkippedWrites;
        private int lastConflictsWritten;
    }
}
