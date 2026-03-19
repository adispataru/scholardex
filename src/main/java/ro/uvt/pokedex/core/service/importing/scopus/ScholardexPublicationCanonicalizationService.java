package ro.uvt.pokedex.core.service.importing.scopus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.isBlank;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.nanosToMillis;
import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash;

@Service
public class ScholardexPublicationCanonicalizationService extends AbstractCanonicalizationService<ScopusPublicationFact, ScholardexPublicationCanonicalizationService.ChunkContext> {

    private static final Logger log = LoggerFactory.getLogger(ScholardexPublicationCanonicalizationService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1_000;
    private static final String DEFAULT_SOURCE_VERSION = "scopus-publication-facts-v1";
    private static final String PIPELINE_KEY = ScholardexCanonicalBuildCheckpointService.PUBLICATION_PIPELINE_KEY;
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-fact-bridge";
    private static final String LINK_REASON_AUTHORSHIP_BRIDGE = "publication-authorship-bridge";
    private static final String LINK_REASON_AUTHOR_FALLBACK = "canonical-author-fallback";
    private static final String LINK_REASON_PUBLICATION_AUTHOR_AFFILIATION_BRIDGE = "publication-author-affiliation-bridge";
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final String REASON_PAPER_AFFILIATION_UNRESOLVED = "PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED";
    private static final Pattern DOI_URL_PREFIX = Pattern.compile("^https?://(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PREFIX = Pattern.compile("^doi:", Pattern.CASE_INSENSITIVE);
    private static final String NULL_CACHE_KEY = "\u0000";

    private final ScopusPublicationFactRepository scopusPublicationFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexEdgeWriterService edgeWriterService;

    @Value("${scopus.canonical.telemetry.heartbeat-seconds:10}")
    private long heartbeatSeconds;
    @Value("${scopus.canonical.telemetry.load-progress-record-interval:10000}")
    private int loadProgressRecordInterval;

    public ScholardexPublicationCanonicalizationService(
            ScopusPublicationFactRepository scopusPublicationFactRepository,
            ScholardexPublicationFactRepository scholardexPublicationFactRepository,
            ScholardexEdgeWriterService edgeWriterService,
            ScholardexSourceLinkService sourceLinkService,
            ScholardexIdentityConflictRepository identityConflictRepository,
            ScholardexCanonicalBuildCheckpointService checkpointService,
            ScopusTouchQueueService touchQueueService
    ) {
        super(sourceLinkService, identityConflictRepository, checkpointService, touchQueueService);
        this.scopusPublicationFactRepository = scopusPublicationFactRepository;
        this.scholardexPublicationFactRepository = scholardexPublicationFactRepository;
        this.edgeWriterService = edgeWriterService;
    }

    // ── Public API (unchanged) ──────────────────────────────────────────────

    public ImportProcessingResult rebuildCanonicalPublicationFactsFromScopusFacts() {
        return rebuild(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult rebuildCanonicalPublicationFactsFromScopusFacts(CanonicalBuildOptions options) {
        return rebuild(options);
    }

    public void upsertFromScopusFact(ScopusPublicationFact scopusFact, ImportProcessingResult result) {
        ChunkContext localContext = new ChunkContext();
        upsertFromScopusFactInternal(scopusFact, result, localContext);
        flushChunkContext(localContext);
    }

    public AuthorBridgeResult bridgeAuthorIds(List<String> sourceAuthorIds, String source) {
        ChunkContext localContext = new ChunkContext();
        return bridgeAuthorIdsInternal(sourceAuthorIds, source, localContext);
    }

    public void syncAuthorshipEdges(ScholardexPublicationFact fact, AuthorBridgeResult bridge) {
        ChunkContext localContext = new ChunkContext();
        upsertPublicationEdges(fact, null, bridge, localContext);
        flushChunkContext(localContext);
    }

    public String buildCanonicalPublicationId(
            String eid,
            String wosId,
            String googleScholarId,
            String userSourceId,
            String doiNormalized,
            String titleNormalized,
            String coverDate,
            String creator,
            String forumId
    ) {
        String material;
        if (!isBlank(eid)) {
            material = "eid|" + normalizeToken(eid);
        } else if (!isBlank(wosId)) {
            material = "wos|" + normalizeToken(wosId);
        } else if (!isBlank(googleScholarId)) {
            material = "gs|" + normalizeToken(googleScholarId);
        } else if (!isBlank(userSourceId)) {
            material = "user|" + normalizeToken(userSourceId);
        } else if (!isBlank(doiNormalized)) {
            material = "doi|" + normalizeToken(doiNormalized);
        } else {
            material = "title|" + normalizeToken(titleNormalized)
                    + "|date|" + normalizeToken(coverDate)
                    + "|creator|" + normalizeToken(creator)
                    + "|forum|" + normalizeToken(forumId);
        }
        return "spub_" + shortHash(material);
    }

    public static String normalizeDoi(String doi) {
        if (doi == null) {
            return null;
        }
        String normalized = doi.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        normalized = DOI_URL_PREFIX.matcher(normalized).replaceFirst("");
        normalized = DOI_PREFIX.matcher(normalized).replaceFirst("");
        normalized = normalized.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String normalized = title.toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD);
        normalized = CanonicalizationSupport.COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = CanonicalizationSupport.NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = CanonicalizationSupport.MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    // ── Abstract hook implementations ───────────────────────────────────────

    @Override protected String getPipelineKey() { return PIPELINE_KEY; }
    @Override protected String getEntityTypeLabel() { return "publication"; }
    @Override protected ScholardexEntityType getEntityType() { return ScholardexEntityType.PUBLICATION; }
    @Override protected int getDefaultChunkSize() { return DEFAULT_CHUNK_SIZE; }
    @Override protected String getDefaultSourceVersion() { return DEFAULT_SOURCE_VERSION; }
    @Override protected long getHeartbeatSeconds() { return heartbeatSeconds; }

    @Override
    protected List<ScopusPublicationFact> loadSourceFacts(CanonicalBuildOptions options) {
        if (!options.fullRescan() && options.incremental()) {
            long startedAtNanos = System.nanoTime();
            log.info("Scholardex publication canonicalization source load started: mode=incremental drainQueues={}", options.drainQueues());
            List<String> touchedIds = touchQueueService.consumePublicationIds(options.drainQueues());
            if (!touchedIds.isEmpty()) {
                List<ScopusPublicationFact> facts = new ArrayList<>(scopusPublicationFactRepository.findByEidIn(touchedIds));
                log.info("Scholardex publication canonicalization source load completed: mode=incremental records={} elapsedMs={}",
                        facts.size(),
                        nanosToMillis(System.nanoTime() - startedAtNanos));
                return facts;
            }
            log.info("Scholardex publication canonicalization incremental run has empty touch queue; skipping source scan.");
            return new ArrayList<>();
        }
        long startedAtNanos = System.nanoTime();
        long totalRecords = scopusPublicationFactRepository.count();
        int pageSize = Math.max(1_000, Math.min(10_000, loadProgressRecordInterval));
        log.info("Scholardex publication canonicalization source load started: mode=full-rescan totalRecords={} pageSize={}", totalRecords, pageSize);
        List<ScopusPublicationFact> out = new ArrayList<>();
        long loaded = 0L;
        long nextLogAt = Math.max(1L, loadProgressRecordInterval);
        int pageNo = 0;
        while (true) {
            Page<ScopusPublicationFact> page = scopusPublicationFactRepository.findAll(PageRequest.of(pageNo, pageSize));
            if (page.isEmpty()) {
                break;
            }
            out.addAll(page.getContent());
            loaded += page.getNumberOfElements();
            if (loaded >= nextLogAt || !page.hasNext()) {
                log.info("Scholardex publication canonicalization source load progress: loaded={} totalRecords={} elapsedMs={}",
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
        log.info("Scholardex publication canonicalization source load completed: mode=full-rescan records={} elapsedMs={}",
                out.size(),
                nanosToMillis(System.nanoTime() - startedAtNanos));
        return out;
    }

    @Override
    protected void sortSourceFacts(List<ScopusPublicationFact> facts) {
        facts.sort(Comparator.comparing(ScopusPublicationFact::getEid, Comparator.nullsLast(String::compareTo)));
    }

    @Override
    protected String lastRecordKey(List<ScopusPublicationFact> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return null;
        }
        return normalizeBlank(chunk.getLast().getEid());
    }

    @Override
    protected ChunkContext createChunkContext() {
        return new ChunkContext();
    }

    @Override
    protected void preloadChunkContext(List<ScopusPublicationFact> chunk, ChunkContext context) {
        Set<String> sourceAuthorIds = new LinkedHashSet<>();
        Set<String> sourceAffiliationIds = new LinkedHashSet<>();
        Set<String> sourcePublicationIds = new LinkedHashSet<>();
        Set<String> eids = new LinkedHashSet<>();
        Set<String> dois = new LinkedHashSet<>();
        for (ScopusPublicationFact publication : chunk) {
            if (publication == null) {
                continue;
            }
            String eid = normalizeBlank(publication.getEid());
            if (eid != null) {
                eids.add(eid);
            }
            String doiNormalized = normalizeDoi(publication.getDoi());
            if (doiNormalized != null) {
                dois.add(doiNormalized);
            }
            String sourceId = normalizeBlank(publication.getSourceRecordId());
            if (sourceId != null) {
                sourcePublicationIds.add(sourceId);
            }
            if (publication.getAuthors() != null) {
                for (String authorId : publication.getAuthors()) {
                    String normalized = normalizeBlank(authorId);
                    if (normalized != null) {
                        sourceAuthorIds.add(normalized);
                    }
                }
            }
            List<String> authorAffiliations = publication.getAuthorAffiliationSourceIds();
            if (authorAffiliations != null) {
                for (String authorAffiliation : authorAffiliations) {
                    for (String affiliationId : splitDash(authorAffiliation, context)) {
                        String normalized = normalizeBlank(affiliationId);
                        if (normalized != null) {
                            sourceAffiliationIds.add(normalized);
                        }
                    }
                }
            }
        }
        preloadSourceLinks(ScholardexEntityType.AUTHOR, sourceAuthorIds, context);
        preloadSourceLinks(ScholardexEntityType.AFFILIATION, sourceAffiliationIds, context);
        preloadSourceLinks(ScholardexEntityType.PUBLICATION, sourcePublicationIds, context);
        preloadPublicationFacts(eids, dois, context);
        context.preloaded = true;
    }

    @Override
    protected void processSourceFact(ScopusPublicationFact sourceFact, ImportProcessingResult result, ChunkContext context) {
        upsertFromScopusFactInternal(sourceFact, result, context);
    }

    @Override
    protected CanonicalBuildChunkTimings flushPendingWrites(long chunkStartedAtNanos, long preloadFinishedAtNanos, long resolveFinishedAtNanos, ChunkContext context) {
        flushChunkContext(context);
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
        log.info("Scholardex publication canonicalization chunk {} writes: publicationFacts[inserted={}, updated={}, recovered={}] sourceLinks={} authorshipEdges={} publicationAuthorAffiliationEdges={} conflicts={} timingsMs[publishInsert={}, publishUpdate={}, publishRecover={}, sourceLinks={}, authorshipEdges={}, publicationAuthorAffiliationEdges={}, conflicts={}]",
                chunkNo,
                context.publicationInsertCount,
                context.publicationUpdateCount,
                context.publicationRecoverCount,
                context.sourceLinkWriteCount,
                context.authorshipEdgeWriteCount,
                context.publicationAuthorAffiliationEdgeWriteCount,
                context.conflictWriteCount,
                context.publicationInsertMs,
                context.publicationUpdateMs,
                context.publicationRecoverMs,
                context.sourceLinkUpsertMs,
                context.authorshipEdgeUpsertMs,
                context.publicationAuthorAffiliationEdgeUpsertMs,
                context.conflictSaveMs);
    }

    // ── Cached normalization helpers (Publication-specific optimization) ─────

    private String normalizeToken(String value, ChunkContext context) {
        if (context != null && context.preloaded) {
            String key = value == null ? NULL_CACHE_KEY : value;
            return context.tokenNormalizationCache.computeIfAbsent(key, ignored -> CanonicalizationSupport.normalizeToken(value));
        }
        return CanonicalizationSupport.normalizeToken(value);
    }

    private String normalizeToken(String value) {
        return CanonicalizationSupport.normalizeToken(value);
    }

    private String normalizeBlank(String value, ChunkContext context) {
        if (context != null && context.preloaded) {
            String key = value == null ? NULL_CACHE_KEY : value;
            if (context.blankNormalizationCache.containsKey(key)) {
                return context.blankNormalizationCache.get(key);
            }
            String normalized = CanonicalizationSupport.normalizeBlank(value);
            context.blankNormalizationCache.put(key, normalized);
            return normalized;
        }
        return CanonicalizationSupport.normalizeBlank(value);
    }

    private String normalizeBlank(String value) {
        return CanonicalizationSupport.normalizeBlank(value);
    }

    private List<String> splitDash(String value, ChunkContext context) {
        String normalized = normalizeBlank(value, context);
        if (normalized == null) {
            return List.of();
        }
        String[] tokens = normalized.split("-");
        List<String> values = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String trimmed = normalizeBlank(token, context);
            if (trimmed != null) {
                values.add(trimmed);
            }
        }
        return values;
    }

    // ── Core entity logic ───────────────────────────────────────────────────

    private void upsertFromScopusFactInternal(ScopusPublicationFact scopusFact, ImportProcessingResult result, ChunkContext context) {
        if (scopusFact == null || isBlank(scopusFact.getEid())) {
            if (result != null) {
                result.markSkipped("missing scopus publication eid");
            }
            return;
        }

        String doiNormalized = normalizeDoi(scopusFact.getDoi());
        ScholardexPublicationFact fact = loadExistingByEidOrDoi(scopusFact.getEid(), doiNormalized, result, context);
        boolean created = fact.getId() == null;

        Instant now = Instant.now();
        AuthorBridgeResult authorBridgeResult = bridgeAuthorIdsInternal(scopusFact.getAuthors(), scopusFact.getSource(), context);
        applyCanonicalPublicationFields(fact, scopusFact, authorBridgeResult, now, context);
        queuePublicationFact(fact, created, context);
        queueSourceLinkCommand(
                ScholardexEntityType.PUBLICATION,
                fact.getSource(),
                fact.getSourceRecordId(),
                fact.getId(),
                ScholardexSourceLinkService.STATE_LINKED,
                LINK_REASON_SCOPUS_BRIDGE,
                fact.getSourceEventId(),
                fact.getSourceBatchId(),
                fact.getSourceCorrelationId(),
                context
        );
        upsertPublicationEdges(fact, scopusFact, authorBridgeResult, context);

        if (result != null) {
            if (created) {
                result.markImported();
            } else {
                result.markUpdated();
            }
        }
    }

    private AuthorBridgeResult bridgeAuthorIdsInternal(List<String> sourceAuthorIds, String source, ChunkContext context) {
        if (sourceAuthorIds == null || sourceAuthorIds.isEmpty()) {
            return new AuthorBridgeResult(List.of(), List.of(), List.of());
        }
        String sourceToken = normalizeToken(source, context);
        Map<String, AuthorBridgeEntry> byCanonicalId = new LinkedHashMap<>();
        LinkedHashSet<String> pendingSourceIds = new LinkedHashSet<>();
        for (String rawAuthorId : sourceAuthorIds) {
            String sourceAuthorId = normalizeBlank(rawAuthorId, context);
            if (sourceAuthorId == null) {
                continue;
            }
            Optional<ScholardexSourceLink> resolved = resolveAuthorSourceLink(source, sourceAuthorId, context);
            if (resolved.isPresent() && !isBlank(resolved.get().getCanonicalEntityId())) {
                String canonicalAuthorId = resolved.get().getCanonicalEntityId();
                byCanonicalId.putIfAbsent(canonicalAuthorId, new AuthorBridgeEntry(canonicalAuthorId, sourceAuthorId, false));
                continue;
            }
            String fallbackAuthorId = buildCanonicalAuthorFallbackId(sourceToken, sourceAuthorId, context);
            byCanonicalId.putIfAbsent(fallbackAuthorId, new AuthorBridgeEntry(fallbackAuthorId, sourceAuthorId, true));
            pendingSourceIds.add(sourceAuthorId);
            queueSourceLinkCommand(
                    ScholardexEntityType.AUTHOR,
                    source,
                    sourceAuthorId,
                    fallbackAuthorId,
                    ScholardexSourceLinkService.STATE_UNMATCHED,
                    LINK_REASON_AUTHOR_FALLBACK,
                    null,
                    null,
                    null,
                    context
            );
        }
        List<String> canonicalAuthorIds = byCanonicalId.keySet().stream().toList();
        List<AuthorBridgeEntry> entries = new ArrayList<>(byCanonicalId.values());
        return new AuthorBridgeResult(canonicalAuthorIds, new ArrayList<>(pendingSourceIds), entries);
    }

    private void applyCanonicalPublicationFields(
            ScholardexPublicationFact fact,
            ScopusPublicationFact scopusFact,
            AuthorBridgeResult authorBridgeResult,
            Instant now,
            ChunkContext context
    ) {
        String doiNormalized = normalizeDoi(scopusFact.getDoi());
        String titleNormalized = normalizeTitle(scopusFact.getTitle());
        String canonicalId = buildCanonicalPublicationId(
                scopusFact.getEid(),
                fact.getWosId(),
                fact.getGoogleScholarId(),
                fact.getUserSourceId(),
                doiNormalized,
                titleNormalized,
                scopusFact.getCoverDate(),
                scopusFact.getCreator(),
                scopusFact.getForumId()
        );
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setId(canonicalId);
        fact.setDoi(scopusFact.getDoi());
        fact.setDoiNormalized(doiNormalized);
        fact.setTitle(scopusFact.getTitle());
        fact.setTitleNormalized(titleNormalized);
        fact.setEid(scopusFact.getEid());
        fact.setSubtype(scopusFact.getSubtype());
        fact.setSubtypeDescription(scopusFact.getSubtypeDescription());
        fact.setScopusSubtype(scopusFact.getScopusSubtype());
        fact.setScopusSubtypeDescription(scopusFact.getScopusSubtypeDescription());
        fact.setCreator(scopusFact.getCreator());
        fact.setAuthorCount(scopusFact.getAuthorCount());
        fact.setAuthorIds(authorBridgeResult.canonicalAuthorIds());
        fact.setPendingAuthorSourceIds(authorBridgeResult.pendingSourceIds());
        fact.setCorrespondingAuthors(scopusFact.getCorrespondingAuthors() == null ? List.of() : new ArrayList<>(scopusFact.getCorrespondingAuthors()));
        fact.setAffiliationIds(scopusFact.getAffiliations() == null ? List.of() : new ArrayList<>(scopusFact.getAffiliations()));
        fact.setForumId(scopusFact.getForumId());
        fact.setVolume(scopusFact.getVolume());
        fact.setIssueIdentifier(scopusFact.getIssueIdentifier());
        fact.setCoverDate(scopusFact.getCoverDate());
        fact.setCoverDisplayDate(scopusFact.getCoverDisplayDate());
        fact.setDescription(scopusFact.getDescription());
        fact.setCitedByCount(scopusFact.getCitedByCount());
        fact.setOpenAccess(scopusFact.getOpenAccess());
        fact.setFreetoread(scopusFact.getFreetoread());
        fact.setFreetoreadLabel(scopusFact.getFreetoreadLabel());
        fact.setFundingId(scopusFact.getFundingId());
        fact.setArticleNumber(scopusFact.getArticleNumber());
        fact.setPageRange(scopusFact.getPageRange());
        fact.setApproved(scopusFact.getApproved());
        fact.setSourceEventId(scopusFact.getSourceEventId());
        fact.setSource(scopusFact.getSource());
        fact.setSourceRecordId(scopusFact.getSourceRecordId());
        fact.setSourceBatchId(scopusFact.getSourceBatchId());
        fact.setSourceCorrelationId(scopusFact.getSourceCorrelationId());
        fact.setUpdatedAt(now);
    }

    private void upsertPublicationEdges(
            ScholardexPublicationFact fact,
            ScopusPublicationFact sourceFact,
            AuthorBridgeResult bridge,
            ChunkContext context
    ) {
        if (fact == null || isBlank(fact.getId()) || bridge == null || bridge.entries().isEmpty()) {
            return;
        }
        Map<String, AuthorBridgeEntry> authorEntriesBySourceId = new LinkedHashMap<>();
        for (AuthorBridgeEntry entry : bridge.entries()) {
            authorEntriesBySourceId.put(normalizeToken(entry.sourceAuthorId(), context), entry);
        }
        List<ScholardexEdgeWriterService.EdgeWriteCommand> publicationAffiliationCommands = new ArrayList<>();
        List<ScholardexEdgeWriterService.EdgeWriteCommand> authorshipCommands = new ArrayList<>();
        LinkedHashSet<String> publicationAffiliationDedup = new LinkedHashSet<>();

        for (AuthorBridgeEntry entry : bridge.entries()) {
            if (isBlank(fact.getSource())) {
                continue;
            }
            String linkState = entry.pendingResolution()
                    ? ScholardexSourceLinkService.STATE_UNMATCHED
                    : ScholardexSourceLinkService.STATE_LINKED;
            String linkReason = entry.pendingResolution()
                    ? LINK_REASON_AUTHOR_FALLBACK
                    : LINK_REASON_AUTHORSHIP_BRIDGE;
            authorshipCommands.add(new ScholardexEdgeWriterService.EdgeWriteCommand(
                    fact.getId(),
                    entry.canonicalAuthorId(),
                    fact.getSource(),
                    buildAuthorshipSourceRecordId(fact.getSourceRecordId(), entry.sourceAuthorId(), context),
                    fact.getSourceEventId(),
                    fact.getSourceBatchId(),
                    fact.getSourceCorrelationId(),
                    linkState,
                    linkReason,
                    false
            ));
        }
        if (!authorshipCommands.isEmpty()) {
            for (ScholardexEdgeWriterService.EdgeWriteCommand command : authorshipCommands) {
                String commandKey = normalizeToken(command.leftId(), context) + "|" + normalizeToken(command.rightId(), context)
                        + "|" + normalizeToken(command.source(), context) + "|" + normalizeToken(command.sourceRecordId(), context);
                context.pendingAuthorshipCommands.put(commandKey, command);
            }
        }
        if (sourceFact == null) {
            return;
        }
        List<String> sourceAuthorIds = sourceFact.getAuthors() == null ? List.of() : sourceFact.getAuthors();
        List<String> sourceAuthorAffiliationIds = sourceFact.getAuthorAffiliationSourceIds() == null
                ? List.of()
                : sourceFact.getAuthorAffiliationSourceIds();
        int size = Math.min(sourceAuthorIds.size(), sourceAuthorAffiliationIds.size());
        for (int i = 0; i < size; i++) {
            String sourceAuthorId = normalizeBlank(sourceAuthorIds.get(i), context);
            if (sourceAuthorId == null) {
                continue;
            }
            AuthorBridgeEntry authorEntry = authorEntriesBySourceId.get(normalizeToken(sourceAuthorId, context));
            if (authorEntry == null || isBlank(authorEntry.canonicalAuthorId())) {
                openPublicationAuthorAffiliationConflict(fact, sourceAuthorId, null, REASON_PAPER_AFFILIATION_UNRESOLVED, context);
                continue;
            }
            List<String> sourceAffiliationIds = splitDash(sourceAuthorAffiliationIds.get(i), context);
            for (String sourceAffiliationId : sourceAffiliationIds) {
                String normalizedSourceAffiliationId = normalizeBlank(sourceAffiliationId, context);
                if (normalizedSourceAffiliationId == null) {
                    continue;
                }
                Optional<ScholardexSourceLink> resolvedAffiliation = resolveAffiliationSourceLink(
                        fact.getSource(),
                        normalizedSourceAffiliationId,
                        context
                );
                if (resolvedAffiliation.isEmpty() || isBlank(resolvedAffiliation.get().getCanonicalEntityId())) {
                    openPublicationAuthorAffiliationConflict(
                            fact,
                            sourceAuthorId,
                            normalizedSourceAffiliationId,
                            REASON_PAPER_AFFILIATION_UNRESOLVED,
                            context
                    );
                    continue;
                }
                String canonicalAffiliationId = resolvedAffiliation.get().getCanonicalEntityId();
                String dedupKey = normalizeToken(fact.getId(), context)
                        + "|" + normalizeToken(authorEntry.canonicalAuthorId(), context)
                        + "|" + normalizeToken(canonicalAffiliationId, context)
                        + "|" + normalizeToken(fact.getSource(), context);
                if (!publicationAffiliationDedup.add(dedupKey)) {
                    continue;
                }
                publicationAffiliationCommands.add(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        fact.getId(),
                        authorEntry.canonicalAuthorId(),
                        canonicalAffiliationId,
                        fact.getSource(),
                        buildPublicationAuthorAffiliationSourceRecordId(
                                fact.getSourceRecordId(),
                                sourceAuthorId,
                                normalizedSourceAffiliationId,
                                context
                        ),
                        fact.getSourceEventId(),
                        fact.getSourceBatchId(),
                        fact.getSourceCorrelationId(),
                        ScholardexSourceLinkService.STATE_LINKED,
                        LINK_REASON_PUBLICATION_AUTHOR_AFFILIATION_BRIDGE,
                        false
                ));
            }
        }
        if (!publicationAffiliationCommands.isEmpty()) {
            for (ScholardexEdgeWriterService.EdgeWriteCommand command : publicationAffiliationCommands) {
                String commandKey = normalizeToken(command.publicationId(), context) + "|" + normalizeToken(command.leftId(), context)
                        + "|" + normalizeToken(command.rightId(), context) + "|" + normalizeToken(command.source(), context)
                        + "|" + normalizeToken(command.sourceRecordId(), context);
                context.pendingPublicationAuthorAffiliationCommands.put(commandKey, command);
            }
        }
    }

    // ── Source link helpers ──────────────────────────────────────────────────

    private Optional<ScholardexSourceLink> resolveAuthorSourceLink(String source, String sourceAuthorId, ChunkContext context) {
        String normalizedSource = normalizeBlank(source, context);
        if (normalizedSource != null) {
            Optional<ScholardexSourceLink> direct = findSourceLink(ScholardexEntityType.AUTHOR, normalizedSource, sourceAuthorId, context);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return findSourceLink(ScholardexEntityType.AUTHOR, SOURCE_SCOPUS, sourceAuthorId, context);
    }

    private Optional<ScholardexSourceLink> resolveAffiliationSourceLink(String source, String sourceAffiliationId, ChunkContext context) {
        String normalizedSource = normalizeBlank(source, context);
        if (normalizedSource != null) {
            Optional<ScholardexSourceLink> direct = findSourceLink(ScholardexEntityType.AFFILIATION, normalizedSource, sourceAffiliationId, context);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return findSourceLink(ScholardexEntityType.AFFILIATION, SOURCE_SCOPUS, sourceAffiliationId, context);
    }

    private Optional<ScholardexSourceLink> findSourceLink(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            ChunkContext context
    ) {
        if (entityType == null || isBlank(source) || isBlank(sourceRecordId)) {
            return Optional.empty();
        }
        ScholardexSourceLinkService.SourceLinkKey key = ScholardexSourceLinkService.SourceLinkKey.of(entityType, source, sourceRecordId);
        ScholardexSourceLink preloaded = context.sourceLinkCache.get(key);
        if (preloaded != null) {
            return Optional.of(preloaded);
        }
        if (context.missingSourceLinkKeys.contains(key)) {
            return Optional.empty();
        }
        Optional<ScholardexSourceLink> link = sourceLinkService.findByKey(entityType, source, sourceRecordId);
        if (link != null && link.isPresent()) {
            context.sourceLinkCache.put(key, link.get());
            return link;
        }
        context.missingSourceLinkKeys.add(key);
        return Optional.empty();
    }

    private void queueSourceLinkCommand(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalId,
            String state,
            String reason,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId,
            ChunkContext context
    ) {
        if (entityType == null || isBlank(source) || isBlank(sourceRecordId)) {
            return;
        }
        String key = entityType.name() + "|" + normalizeToken(source, context) + "|" + normalizeToken(sourceRecordId, context);
        context.pendingSourceLinkCommands.put(key, new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                entityType,
                source,
                sourceRecordId,
                canonicalId,
                state,
                reason,
                sourceEventId,
                sourceBatchId,
                sourceCorrelationId,
                false
        ));
    }

    // ── Preload and flush helpers ───────────────────────────────────────────

    private void preloadSourceLinks(ScholardexEntityType entityType, Set<String> sourceRecordIds, ChunkContext context) {
        if (sourceRecordIds == null || sourceRecordIds.isEmpty()) {
            return;
        }
        for (ScholardexSourceLink sourceLink : sourceLinkService.findByEntityTypeAndSourceRecordIds(entityType, sourceRecordIds)) {
            context.sourceLinkCache.put(
                    ScholardexSourceLinkService.SourceLinkKey.of(
                            sourceLink.getEntityType(),
                            sourceLink.getSource(),
                            sourceLink.getSourceRecordId()
                    ),
                    sourceLink
            );
        }
    }

    private void preloadPublicationFacts(Set<String> eids, Set<String> doiNormalizedValues, ChunkContext context) {
        if ((eids == null || eids.isEmpty()) && (doiNormalizedValues == null || doiNormalizedValues.isEmpty())) {
            return;
        }
        if (eids != null && !eids.isEmpty()) {
            for (ScholardexPublicationFact fact : scholardexPublicationFactRepository.findAllByEidIn(eids)) {
                indexPublicationFact(fact, context);
            }
        }
        if (doiNormalizedValues != null && !doiNormalizedValues.isEmpty()) {
            for (ScholardexPublicationFact fact : scholardexPublicationFactRepository.findAllByDoiNormalizedIn(doiNormalizedValues)) {
                indexPublicationFact(fact, context);
            }
        }
    }

    private void indexPublicationFact(ScholardexPublicationFact fact, ChunkContext context) {
        if (fact == null) {
            return;
        }
        if (!isBlank(fact.getId())) {
            context.existingPublicationIds.add(fact.getId());
            context.pendingPublicationFacts.put(fact.getId(), fact);
        }
        String eid = normalizeBlank(fact.getEid());
        if (eid != null) {
            context.publicationByEid.put(eid, fact);
        }
        String doi = normalizeBlank(fact.getDoiNormalized());
        if (doi != null) {
            context.publicationByDoi.put(doi, fact);
        }
    }

    private void queuePublicationFact(ScholardexPublicationFact fact, boolean created, ChunkContext context) {
        if (fact == null || isBlank(fact.getId())) {
            return;
        }
        context.pendingPublicationFacts.put(fact.getId(), fact);
        if (created && !context.existingPublicationIds.contains(fact.getId())) {
            context.pendingInsertPublicationIds.add(fact.getId());
        } else {
            context.existingPublicationIds.add(fact.getId());
            context.pendingInsertPublicationIds.remove(fact.getId());
        }
        String eid = normalizeBlank(fact.getEid());
        if (eid != null) {
            context.publicationByEid.put(eid, fact);
        }
        String doi = normalizeBlank(fact.getDoiNormalized());
        if (doi != null) {
            context.publicationByDoi.put(doi, fact);
        }
    }

    private ScholardexPublicationFact loadExistingByEidOrDoi(String eid, String doiNormalized, ImportProcessingResult result, ChunkContext context) {
        if (context != null && context.preloaded) {
            String normalizedEid = normalizeBlank(eid, context);
            if (normalizedEid != null) {
                ScholardexPublicationFact byEid = context.publicationByEid.get(normalizedEid);
                if (byEid != null) {
                    return byEid;
                }
            }
            if (!isBlank(doiNormalized)) {
                ScholardexPublicationFact byDoi = context.publicationByDoi.get(doiNormalized);
                if (byDoi != null) {
                    return byDoi;
                }
            }
            return new ScholardexPublicationFact();
        }
        Optional<ScholardexPublicationFact> existingByEid = scholardexPublicationFactRepository.findByEid(eid);
        if (existingByEid.isPresent()) {
            return existingByEid.get();
        }
        return findSingleByDoi(doiNormalized, result).orElseGet(ScholardexPublicationFact::new);
    }

    private Optional<ScholardexPublicationFact> findSingleByDoi(String doiNormalized, ImportProcessingResult result) {
        if (isBlank(doiNormalized)) {
            return Optional.empty();
        }
        List<ScholardexPublicationFact> byDoi = new ArrayList<>(scholardexPublicationFactRepository.findAllByDoiNormalized(doiNormalized));
        if (byDoi.isEmpty()) {
            return Optional.empty();
        }
        byDoi.sort(Comparator.comparing(ScholardexPublicationFact::getId, Comparator.nullsLast(String::compareTo)));
        if (byDoi.size() > 1 && result != null) {
            result.markSkipped("ambiguous-existing-doi:" + doiNormalized);
        }
        return Optional.of(byDoi.getFirst());
    }

    private void flushChunkContext(ChunkContext context) {
        if (context == null) {
            return;
        }
        flushPublicationFacts(context);
        if (!context.pendingSourceLinkCommands.isEmpty()) {
            long startedAt = System.nanoTime();
            sourceLinkService.batchUpsertWithState(
                    context.pendingSourceLinkCommands.values(),
                    context.sourceLinkCache,
                    false
            );
            context.sourceLinkUpsertMs += nanosToMillis(System.nanoTime() - startedAt);
            context.sourceLinkWriteCount += context.pendingSourceLinkCommands.size();
        }
        if (!context.pendingAuthorshipCommands.isEmpty()) {
            long startedAt = System.nanoTime();
            edgeWriterService.batchUpsertAuthorshipEdges(
                    new ArrayList<>(context.pendingAuthorshipCommands.values()),
                    Map.of(),
                    context.sourceLinkCache,
                    false
            );
            context.authorshipEdgeUpsertMs += nanosToMillis(System.nanoTime() - startedAt);
            context.authorshipEdgeWriteCount += context.pendingAuthorshipCommands.size();
        }
        if (!context.pendingPublicationAuthorAffiliationCommands.isEmpty()) {
            long startedAt = System.nanoTime();
            edgeWriterService.batchUpsertPublicationAuthorAffiliationEdges(
                    new ArrayList<>(context.pendingPublicationAuthorAffiliationCommands.values()),
                    Map.of(),
                    context.sourceLinkCache,
                    false
            );
            context.publicationAuthorAffiliationEdgeUpsertMs += nanosToMillis(System.nanoTime() - startedAt);
            context.publicationAuthorAffiliationEdgeWriteCount += context.pendingPublicationAuthorAffiliationCommands.size();
        }
        if (!context.pendingIdentityConflicts.isEmpty()) {
            long startedAt = System.nanoTime();
            identityConflictRepository.saveAll(context.pendingIdentityConflicts.values());
            context.conflictSaveMs += nanosToMillis(System.nanoTime() - startedAt);
            context.conflictWriteCount += context.pendingIdentityConflicts.size();
            for (ScholardexIdentityConflict conflict : context.pendingIdentityConflicts.values()) {
                H19CanonicalMetrics.recordConflictCreated(
                        conflict.getEntityType().name(),
                        conflict.getIncomingSource(),
                        conflict.getReasonCode()
                );
            }
        }
    }

    private void flushPublicationFacts(ChunkContext context) {
        if (context.pendingPublicationFacts.isEmpty()) {
            return;
        }
        List<ScholardexPublicationFact> insertFacts = new ArrayList<>();
        List<ScholardexPublicationFact> updateFacts = new ArrayList<>();
        for (ScholardexPublicationFact fact : context.pendingPublicationFacts.values()) {
            if (!isBlank(fact.getId()) && context.pendingInsertPublicationIds.contains(fact.getId())) {
                insertFacts.add(fact);
            } else {
                updateFacts.add(fact);
            }
        }

        if (!insertFacts.isEmpty()) {
            try {
                long startedAt = System.nanoTime();
                scholardexPublicationFactRepository.insert(insertFacts);
                context.publicationInsertMs += nanosToMillis(System.nanoTime() - startedAt);
                context.publicationInsertCount += insertFacts.size();
            } catch (DuplicateKeyException ex) {
                log.warn("Scholardex publication canonicalization chunk insert hit duplicate key; falling back to per-record recovery path for {} facts.",
                        insertFacts.size());
                for (ScholardexPublicationFact fact : insertFacts) {
                    recoverPublicationWrite(fact, context);
                }
            }
        }

        if (!updateFacts.isEmpty()) {
            try {
                long startedAt = System.nanoTime();
                scholardexPublicationFactRepository.saveAll(updateFacts);
                context.publicationUpdateMs += nanosToMillis(System.nanoTime() - startedAt);
                context.publicationUpdateCount += updateFacts.size();
            } catch (DuplicateKeyException ex) {
                log.warn("Scholardex publication canonicalization chunk update saveAll hit duplicate key; falling back to per-record recovery path for {} facts.",
                        updateFacts.size());
                for (ScholardexPublicationFact fact : updateFacts) {
                    recoverPublicationWrite(fact, context);
                }
            }
        }
    }

    private void recoverPublicationWrite(ScholardexPublicationFact fact, ChunkContext context) {
        if (fact == null) {
            return;
        }
        String doiNormalized = normalizeDoi(fact.getDoi());
        try {
            long startedAt = System.nanoTime();
            scholardexPublicationFactRepository.save(fact);
            context.publicationRecoverMs += nanosToMillis(System.nanoTime() - startedAt);
            context.publicationRecoverCount++;
        } catch (DuplicateKeyException duplicateKeyException) {
            Optional<ScholardexPublicationFact> existingByDoi = findSingleByDoi(doiNormalized, null);
            if (existingByDoi.isPresent()) {
                ScholardexPublicationFact recovered = existingByDoi.get();
                applyCanonicalPublicationFields(
                        recovered,
                        toScopusBridgeFact(fact),
                        new AuthorBridgeResult(
                                fact.getAuthorIds() == null ? List.of() : fact.getAuthorIds(),
                                fact.getPendingAuthorSourceIds() == null ? List.of() : fact.getPendingAuthorSourceIds(),
                                List.of()
                        ),
                        Instant.now(),
                        context
                );
                long startedAt = System.nanoTime();
                scholardexPublicationFactRepository.save(recovered);
                context.publicationRecoverMs += nanosToMillis(System.nanoTime() - startedAt);
                context.publicationRecoverCount++;
                queuePublicationFact(recovered, false, context);
            } else {
                throw duplicateKeyException;
            }
        }
    }

    // ── Other private helpers ────────────────────────────────────────────────

    private void openPublicationAuthorAffiliationConflict(
            ScholardexPublicationFact fact,
            String sourceAuthorId,
            String sourceAffiliationId,
            String reasonCode,
            ChunkContext context
    ) {
        if (fact == null || isBlank(fact.getSource())) {
            return;
        }
        String sourceRecordId = buildPublicationAuthorAffiliationSourceRecordId(
                fact.getSourceRecordId(),
                sourceAuthorId,
                sourceAffiliationId == null ? "missing" : sourceAffiliationId,
                context
        );
        String normalizedSource = CanonicalizationSupport.normalizeBlank(fact.getSource());
        String normalizedRecordId = CanonicalizationSupport.normalizeBlank(sourceRecordId);
        if (normalizedSource == null || normalizedRecordId == null) {
            return;
        }
        String key = ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION.name()
                + "|" + normalizeToken(normalizedSource, context)
                + "|" + normalizeToken(normalizedRecordId, context)
                + "|" + normalizeToken(reasonCode, context);
        ScholardexIdentityConflict conflict = context.pendingIdentityConflicts.get(key);
        if (conflict == null) {
            conflict = new ScholardexIdentityConflict();
            conflict.setEntityType(ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION);
            conflict.setIncomingSource(normalizedSource);
            conflict.setIncomingSourceRecordId(normalizedRecordId);
            conflict.setReasonCode(reasonCode);
            conflict.setStatus(CanonicalizationSupport.STATUS_OPEN);
            conflict.setCandidateCanonicalIds(List.of());
            conflict.setDetectedAt(Instant.now());
            context.pendingIdentityConflicts.put(key, conflict);
        }
        conflict.setSourceEventId(CanonicalizationSupport.normalizeBlank(fact.getSourceEventId()));
        conflict.setSourceBatchId(CanonicalizationSupport.normalizeBlank(fact.getSourceBatchId()));
        conflict.setSourceCorrelationId(CanonicalizationSupport.normalizeBlank(fact.getSourceCorrelationId()));
    }

    private String buildCanonicalAuthorFallbackId(String sourceToken, String sourceAuthorId, ChunkContext context) {
        String normalizedSource = isBlank(sourceToken) ? "unknown" : sourceToken;
        return "sauth_" + shortHash("source|" + normalizedSource + "|author|" + normalizeToken(sourceAuthorId, context));
    }

    private String buildAuthorshipSourceRecordId(String publicationSourceRecordId, String sourceAuthorId, ChunkContext context) {
        return normalizeToken(publicationSourceRecordId, context) + "::author::" + normalizeToken(sourceAuthorId, context);
    }

    private String buildPublicationAuthorAffiliationSourceRecordId(
            String publicationSourceRecordId,
            String sourceAuthorId,
            String sourceAffiliationId,
            ChunkContext context
    ) {
        return normalizeToken(publicationSourceRecordId, context)
                + "::author::" + normalizeToken(sourceAuthorId, context)
                + "::affiliation::" + normalizeToken(sourceAffiliationId, context);
    }

    private ScopusPublicationFact toScopusBridgeFact(ScholardexPublicationFact fact) {
        ScopusPublicationFact bridge = new ScopusPublicationFact();
        bridge.setEid(fact.getEid());
        bridge.setDoi(fact.getDoi());
        bridge.setTitle(fact.getTitle());
        bridge.setSubtype(fact.getSubtype());
        bridge.setSubtypeDescription(fact.getSubtypeDescription());
        bridge.setScopusSubtype(fact.getScopusSubtype());
        bridge.setScopusSubtypeDescription(fact.getScopusSubtypeDescription());
        bridge.setCreator(fact.getCreator());
        bridge.setAuthorCount(fact.getAuthorCount());
        bridge.setCorrespondingAuthors(fact.getCorrespondingAuthors());
        bridge.setAffiliations(fact.getAffiliationIds());
        bridge.setForumId(fact.getForumId());
        bridge.setVolume(fact.getVolume());
        bridge.setIssueIdentifier(fact.getIssueIdentifier());
        bridge.setCoverDate(fact.getCoverDate());
        bridge.setCoverDisplayDate(fact.getCoverDisplayDate());
        bridge.setDescription(fact.getDescription());
        bridge.setCitedByCount(fact.getCitedByCount());
        bridge.setOpenAccess(fact.getOpenAccess());
        bridge.setFreetoread(fact.getFreetoread());
        bridge.setFreetoreadLabel(fact.getFreetoreadLabel());
        bridge.setFundingId(fact.getFundingId());
        bridge.setArticleNumber(fact.getArticleNumber());
        bridge.setPageRange(fact.getPageRange());
        bridge.setApproved(fact.getApproved());
        bridge.setSourceEventId(fact.getSourceEventId());
        bridge.setSource(fact.getSource());
        bridge.setSourceRecordId(fact.getSourceRecordId());
        bridge.setSourceBatchId(fact.getSourceBatchId());
        bridge.setSourceCorrelationId(fact.getSourceCorrelationId());
        return bridge;
    }

    // ── Records and chunk context ───────────────────────────────────────────

    public record AuthorBridgeResult(
            List<String> canonicalAuthorIds,
            List<String> pendingSourceIds,
            List<AuthorBridgeEntry> entries
    ) {}

    public record AuthorBridgeEntry(
            String canonicalAuthorId,
            String sourceAuthorId,
            boolean pendingResolution
    ) {}

    static class ChunkContext {
        private boolean preloaded = false;
        private final Map<String, String> tokenNormalizationCache = new HashMap<>();
        private final Map<String, String> blankNormalizationCache = new HashMap<>();
        private final Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> sourceLinkCache = new HashMap<>();
        private final Set<ScholardexSourceLinkService.SourceLinkKey> missingSourceLinkKeys = new LinkedHashSet<>();
        private final Map<String, ScholardexPublicationFact> publicationByEid = new HashMap<>();
        private final Map<String, ScholardexPublicationFact> publicationByDoi = new HashMap<>();
        private final Set<String> existingPublicationIds = new LinkedHashSet<>();
        private final Set<String> pendingInsertPublicationIds = new LinkedHashSet<>();
        private final Map<String, ScholardexPublicationFact> pendingPublicationFacts = new LinkedHashMap<>();
        private final Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> pendingSourceLinkCommands = new LinkedHashMap<>();
        private final Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> pendingAuthorshipCommands = new LinkedHashMap<>();
        private final Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> pendingPublicationAuthorAffiliationCommands = new LinkedHashMap<>();
        private final Map<String, ScholardexIdentityConflict> pendingIdentityConflicts = new LinkedHashMap<>();
        private int publicationInsertCount = 0;
        private int publicationUpdateCount = 0;
        private int publicationRecoverCount = 0;
        private int sourceLinkWriteCount = 0;
        private int authorshipEdgeWriteCount = 0;
        private int publicationAuthorAffiliationEdgeWriteCount = 0;
        private int conflictWriteCount = 0;
        private long publicationInsertMs = 0L;
        private long publicationUpdateMs = 0L;
        private long publicationRecoverMs = 0L;
        private long sourceLinkUpsertMs = 0L;
        private long authorshipEdgeUpsertMs = 0L;
        private long publicationAuthorAffiliationEdgeUpsertMs = 0L;
        private long conflictSaveMs = 0L;
    }
}
