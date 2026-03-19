package ro.uvt.pokedex.core.service.importing.scopus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.safeList;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash;

@Service
public class ScholardexAffiliationCanonicalizationService extends AbstractCanonicalizationService<ScopusAffiliationFact, ScholardexAffiliationCanonicalizationService.ChunkContext> {

    private static final Logger log = LoggerFactory.getLogger(ScholardexAffiliationCanonicalizationService.class);
    private static final int DEFAULT_CHUNK_SIZE = 5_000;
    private static final String DEFAULT_SOURCE_VERSION = "scopus-affiliation-facts-v1";
    private static final String PIPELINE_KEY = ScholardexCanonicalBuildCheckpointService.AFFILIATION_PIPELINE_KEY;
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-affiliation-bridge";
    private static final String CONFLICT_SOURCE_ID_COLLISION = "SOURCE_ID_COLLISION";

    private final ScopusAffiliationFactRepository scopusAffiliationFactRepository;
    private final ScholardexAffiliationFactRepository scholardexAffiliationFactRepository;

    @Value("${scopus.canonical.telemetry.heartbeat-seconds:10}")
    private long heartbeatSeconds;

    public ScholardexAffiliationCanonicalizationService(
            ScopusAffiliationFactRepository scopusAffiliationFactRepository,
            ScholardexAffiliationFactRepository scholardexAffiliationFactRepository,
            ScholardexSourceLinkService sourceLinkService,
            ScholardexIdentityConflictRepository identityConflictRepository,
            ScholardexCanonicalBuildCheckpointService checkpointService,
            ScopusTouchQueueService touchQueueService
    ) {
        super(sourceLinkService, identityConflictRepository, checkpointService, touchQueueService);
        this.scopusAffiliationFactRepository = scopusAffiliationFactRepository;
        this.scholardexAffiliationFactRepository = scholardexAffiliationFactRepository;
    }

    // ── Public API (unchanged) ──────────────────────────────────────────────

    public ImportProcessingResult rebuildCanonicalAffiliationFactsFromScopusFacts() {
        return rebuild(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult rebuildCanonicalAffiliationFactsFromScopusFacts(CanonicalBuildOptions options) {
        return rebuild(options);
    }

    public void upsertFromScopusFact(ScopusAffiliationFact sourceFact, ImportProcessingResult result) {
        ChunkContext context = createChunkContext();
        preloadChunkContext(List.of(sourceFact), context);
        processSourceFact(sourceFact, result, context);
        flushPendingWrites(0, 0, 0, context);
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

    // ── Abstract hook implementations ───────────────────────────────────────

    @Override protected String getPipelineKey() { return PIPELINE_KEY; }
    @Override protected String getEntityTypeLabel() { return "affiliation"; }
    @Override protected ScholardexEntityType getEntityType() { return ScholardexEntityType.AFFILIATION; }
    @Override protected int getDefaultChunkSize() { return DEFAULT_CHUNK_SIZE; }
    @Override protected String getDefaultSourceVersion() { return DEFAULT_SOURCE_VERSION; }
    @Override protected long getHeartbeatSeconds() { return heartbeatSeconds; }

    @Override
    protected List<ScopusAffiliationFact> loadSourceFacts(CanonicalBuildOptions options) {
        if (!options.fullRescan() && options.incremental()) {
            long startedAtNanos = System.nanoTime();
            log.info("Scholardex affiliation canonicalization source load started: mode=incremental drainQueues={}", options.drainQueues());
            List<String> touchedIds = touchQueueService.consumeAffiliationIds(options.drainQueues());
            if (!touchedIds.isEmpty()) {
                List<ScopusAffiliationFact> facts = new ArrayList<>(scopusAffiliationFactRepository.findByAfidIn(touchedIds));
                log.info("Scholardex affiliation canonicalization source load completed: mode=incremental records={} elapsedMs={}",
                        facts.size(), nanosToMillis(System.nanoTime() - startedAtNanos));
                return facts;
            }
            log.info("Scholardex affiliation canonicalization incremental run has empty touch queue; skipping source scan.");
            return new ArrayList<>();
        }
        long startedAtNanos = System.nanoTime();
        log.info("Scholardex affiliation canonicalization source load started: mode=full-rescan");
        List<ScopusAffiliationFact> facts = new ArrayList<>(scopusAffiliationFactRepository.findAll());
        log.info("Scholardex affiliation canonicalization source load completed: mode=full-rescan records={} elapsedMs={}",
                facts.size(), nanosToMillis(System.nanoTime() - startedAtNanos));
        return facts;
    }

    @Override
    protected void sortSourceFacts(List<ScopusAffiliationFact> facts) {
        facts.sort(Comparator.comparing(ScopusAffiliationFact::getAfid, Comparator.nullsLast(String::compareTo)));
    }

    @Override
    protected String lastRecordKey(List<ScopusAffiliationFact> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return null;
        }
        return normalizeBlank(chunk.getLast().getAfid());
    }

    @Override
    protected ChunkContext createChunkContext() {
        return new ChunkContext();
    }

    @Override
    protected void preloadChunkContext(List<ScopusAffiliationFact> chunk, ChunkContext context) {
        Set<String> sourceRecordIds = new LinkedHashSet<>();
        for (ScopusAffiliationFact sourceFact : chunk) {
            String sourceRecordId = normalizeBlank(sourceFact == null ? null : sourceFact.getAfid());
            if (sourceRecordId != null) {
                sourceRecordIds.add(sourceRecordId);
            }
        }
        if (sourceRecordIds.isEmpty()) {
            return;
        }

        List<ScholardexSourceLink> preloadedSourceLinks = sourceLinkService.findByEntityTypeAndSourceRecordIds(
                ScholardexEntityType.AFFILIATION, sourceRecordIds);
        Set<String> canonicalIds = new LinkedHashSet<>();
        for (ScholardexSourceLink link : preloadedSourceLinks) {
            if (link == null || link.getEntityType() != ScholardexEntityType.AFFILIATION) {
                continue;
            }
            String source = sourceLinkService.normalizeSource(link.getSource());
            String sourceRecordId = normalizeBlank(link.getSourceRecordId());
            if (source == null || sourceRecordId == null) {
                continue;
            }
            ScholardexSourceLinkService.SourceLinkKey key = ScholardexSourceLinkService.SourceLinkKey.of(
                    ScholardexEntityType.AFFILIATION, source, sourceRecordId);
            context.preloadedSourceLinks.putIfAbsent(key, link);
            String canonicalId = normalizeBlank(link.getCanonicalEntityId());
            if (canonicalId != null) {
                canonicalIds.add(canonicalId);
            }
        }

        if (!canonicalIds.isEmpty()) {
            for (ScholardexAffiliationFact fact : scholardexAffiliationFactRepository.findByIdIn(canonicalIds)) {
                if (fact != null && !isBlank(fact.getId())) {
                    context.affiliationByCanonicalId.put(fact.getId(), fact);
                }
            }
        }

        List<ScholardexAffiliationFact> existingBySource = scholardexAffiliationFactRepository.findByScopusAffiliationIdsIn(sourceRecordIds);
        for (ScholardexAffiliationFact fact : existingBySource) {
            if (fact == null) {
                continue;
            }
            if (!isBlank(fact.getId())) {
                context.affiliationByCanonicalId.putIfAbsent(fact.getId(), fact);
            }
            for (String sourceId : safeList(fact.getScopusAffiliationIds())) {
                String normalized = normalizeBlank(sourceId);
                if (normalized != null && sourceRecordIds.contains(normalized)) {
                    context.affiliationBySourceId.putIfAbsent(normalized, fact);
                }
            }
        }
    }

    @Override
    protected void processSourceFact(ScopusAffiliationFact sourceFact, ImportProcessingResult result, ChunkContext context) {
        String sourceRecordId = normalizeBlank(sourceFact == null ? null : sourceFact.getAfid());
        if (sourceFact == null || sourceRecordId == null) {
            if (result != null) {
                result.markSkipped("missing scopus affiliation id");
            }
            return;
        }

        String normalizedSource = sourceLinkService.normalizeSource(sourceFact.getSource());
        ScholardexSourceLinkService.SourceLinkKey sourceLinkKey = normalizedSource == null
                ? null
                : ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AFFILIATION, normalizedSource, sourceRecordId);
        Optional<ScholardexSourceLink> existingSourceLink = sourceLinkKey == null
                ? Optional.empty()
                : Optional.ofNullable(context.preloadedSourceLinks.get(sourceLinkKey));
        ScholardexAffiliationFact existingBySource = context.affiliationBySourceId.get(sourceRecordId);
        String canonicalId = existingSourceLink.map(ScholardexSourceLink::getCanonicalEntityId)
                .or(() -> Optional.ofNullable(existingBySource).map(ScholardexAffiliationFact::getId))
                .orElseGet(() -> buildCanonicalAffiliationId(sourceRecordId, sourceFact.getName(), sourceFact.getCity(), sourceFact.getCountry()));

        if (existingSourceLink.isPresent() && existingSourceLink.get().getCanonicalEntityId() != null
                && !existingSourceLink.get().getCanonicalEntityId().equals(canonicalId)) {
            context.pendingConflicts.add(buildConflict(
                    sourceFact.getSource(), sourceRecordId, CONFLICT_SOURCE_ID_COLLISION,
                    List.of(existingSourceLink.get().getCanonicalEntityId(), canonicalId),
                    sourceFact.getSourceEventId(), sourceFact.getSourceBatchId(), sourceFact.getSourceCorrelationId()));
            if (result != null) {
                result.markSkipped("affiliation-source-id-collision:" + sourceRecordId);
            }
            return;
        }

        ScholardexAffiliationFact target = context.pendingAffiliationSaves.get(canonicalId);
        if (target == null) {
            target = context.affiliationByCanonicalId.get(canonicalId);
        }
        if (target == null) {
            target = new ScholardexAffiliationFact();
        }
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
        context.pendingAffiliationSaves.put(target.getId(), target);
        context.affiliationByCanonicalId.put(target.getId(), target);
        context.affiliationBySourceId.put(sourceRecordId, target);
        upsertSourceLinkCommand(sourceFact, sourceRecordId, target.getId(), sourceLinkKey, context);

        if (result != null) {
            if (created) {
                result.markImported();
            } else {
                result.markUpdated();
            }
        }
    }

    @Override
    protected CanonicalBuildChunkTimings flushPendingWrites(long chunkStartedAtNanos, long preloadFinishedAtNanos, long resolveFinishedAtNanos, ChunkContext context) {
        if (!context.pendingAffiliationSaves.isEmpty()) {
            scholardexAffiliationFactRepository.saveAll(context.pendingAffiliationSaves.values());
        }
        if (!context.pendingSourceLinkCommands.isEmpty()) {
            sourceLinkService.batchUpsertWithState(
                    context.pendingSourceLinkCommands.values(),
                    context.preloadedSourceLinks,
                    false
            );
        }
        if (!context.pendingConflicts.isEmpty()) {
            identityConflictRepository.saveAll(context.pendingConflicts);
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

    // ── Private helpers ─────────────────────────────────────────────────────

    private String normalizeAlias(String name, String city, String country) {
        String alias = normalizeToken(normalizeName(name)) + "|" + normalizeToken(city) + "|" + normalizeToken(country);
        return alias.equals("||") ? null : alias;
    }

    private void upsertSourceLinkCommand(
            ScopusAffiliationFact sourceFact,
            String sourceRecordId,
            String canonicalId,
            ScholardexSourceLinkService.SourceLinkKey sourceLinkKey,
            ChunkContext context
    ) {
        if (sourceLinkKey == null) {
            return;
        }
        ScholardexSourceLinkService.SourceLinkUpsertCommand command = new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                ScholardexEntityType.AFFILIATION,
                sourceLinkKey.source(),
                sourceRecordId,
                canonicalId,
                ScholardexSourceLinkService.STATE_LINKED,
                LINK_REASON_SCOPUS_BRIDGE,
                sourceFact.getSourceEventId(),
                sourceFact.getSourceBatchId(),
                sourceFact.getSourceCorrelationId(),
                false
        );
        context.pendingSourceLinkCommands.put(sourceLinkKey, command);
    }

    // ── Chunk context ───────────────────────────────────────────────────────

    static final class ChunkContext {
        private final Map<String, ScholardexAffiliationFact> affiliationByCanonicalId = new HashMap<>();
        private final Map<String, ScholardexAffiliationFact> affiliationBySourceId = new HashMap<>();
        private final Map<String, ScholardexAffiliationFact> pendingAffiliationSaves = new LinkedHashMap<>();
        private final Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> preloadedSourceLinks = new LinkedHashMap<>();
        private final Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingSourceLinkCommands = new LinkedHashMap<>();
        private final List<ScholardexIdentityConflict> pendingConflicts = new ArrayList<>();
    }
}
