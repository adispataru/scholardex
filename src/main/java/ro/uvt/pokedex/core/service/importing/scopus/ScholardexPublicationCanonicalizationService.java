package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ScholardexPublicationCanonicalizationService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexPublicationCanonicalizationService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1_000;
    private static final String DEFAULT_SOURCE_VERSION = "scopus-publication-facts-v1";
    private static final String PIPELINE_KEY = ScholardexCanonicalBuildCheckpointService.PUBLICATION_PIPELINE_KEY;
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-fact-bridge";
    private static final String LINK_REASON_AUTHORSHIP_BRIDGE = "publication-authorship-bridge";
    private static final String LINK_REASON_AUTHOR_FALLBACK = "canonical-author-fallback";
    private static final String LINK_REASON_PUBLICATION_AUTHOR_AFFILIATION_BRIDGE = "publication-author-affiliation-bridge";
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final String STATUS_OPEN = "OPEN";
    private static final String REASON_PAPER_AFFILIATION_UNRESOLVED = "PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED";
    private static final Pattern DOI_URL_PREFIX = Pattern.compile("^https?://(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PREFIX = Pattern.compile("^doi:", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final String NULL_CACHE_KEY = "\u0000";

    private final ScopusPublicationFactRepository scopusPublicationFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexIdentityConflictRepository identityConflictRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexEdgeWriterService edgeWriterService;
    private final ScholardexCanonicalBuildCheckpointService checkpointService;
    private final ScopusTouchQueueService touchQueueService;
    @Value("${scopus.canonical.telemetry.heartbeat-seconds:10}")
    private long heartbeatSeconds;
    @Value("${scopus.canonical.telemetry.load-progress-record-interval:10000}")
    private int loadProgressRecordInterval;

    public ImportProcessingResult rebuildCanonicalPublicationFactsFromScopusFacts() {
        return rebuildCanonicalPublicationFactsFromScopusFacts(CanonicalBuildOptions.defaults());
    }

    public ImportProcessingResult rebuildCanonicalPublicationFactsFromScopusFacts(CanonicalBuildOptions options) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        CanonicalBuildOptions effectiveOptions = options == null ? CanonicalBuildOptions.defaults() : options;
        int chunkSize = effectiveOptions.chunkSizeOverride() == null || effectiveOptions.chunkSizeOverride() <= 0
                ? DEFAULT_CHUNK_SIZE
                : effectiveOptions.chunkSizeOverride();
        log.info("Scholardex publication canonicalization phase started: mode={} fullRescan={} chunkSize={} useCheckpoint={}",
                effectiveOptions.incremental() ? "INCREMENTAL" : "FULL",
                effectiveOptions.fullRescan(),
                chunkSize,
                effectiveOptions.useCheckpoint());
        List<ScopusPublicationFact> scopusFacts = loadSourceFacts(effectiveOptions);
        log.info("Scholardex publication canonicalization sort started: records={}", scopusFacts.size());
        scopusFacts.sort(Comparator.comparing(ScopusPublicationFact::getEid, Comparator.nullsLast(String::compareTo)));
        log.info("Scholardex publication canonicalization sort completed: records={}", scopusFacts.size());
        int total = scopusFacts.size();
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
            log.info("Scholardex publication canonicalization skipped: totalRecords={}, totalBatches={}, startBatch={}, checkpointLastCompletedBatch={}",
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

            PublicationChunkExecution chunkExecution = processChunk(scopusFacts.subList(from, to), result, chunkNo, totalBatches, total);
            CanonicalBuildChunkTimings timings = chunkExecution.timings();
            ChunkContext chunkContext = chunkExecution.context();
            batchesProcessed++;
            result.setEndBatch(batchIndex);
            result.setBatchesProcessed(batchesProcessed);

            if (effectiveOptions.useCheckpoint()) {
                checkpointService.upsertCheckpoint(
                        PIPELINE_KEY,
                        batchIndex,
                        chunkSize,
                        lastRecordKey(scopusFacts.subList(from, to)),
                        runId,
                        sourceVersion
                );
            }
            log.info("Scholardex publication canonicalization chunk {} complete [batch={} / totalBatches={}]: records={} imported={} updated={} skipped={} errors={} timingsMs[preload={}, resolve={}, upsert={}, save={}, total={}]",
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
            log.info("Scholardex publication canonicalization chunk {} writes: publicationFacts[inserted={}, updated={}, recovered={}] sourceLinks={} authorshipEdges={} publicationAuthorAffiliationEdges={} conflicts={} timingsMs[publishInsert={}, publishUpdate={}, publishRecover={}, sourceLinks={}, authorshipEdges={}, publicationAuthorAffiliationEdges={}, conflicts={}]",
                    chunkNo,
                    chunkContext.publicationInsertCount,
                    chunkContext.publicationUpdateCount,
                    chunkContext.publicationRecoverCount,
                    chunkContext.sourceLinkWriteCount,
                    chunkContext.authorshipEdgeWriteCount,
                    chunkContext.publicationAuthorAffiliationEdgeWriteCount,
                    chunkContext.conflictWriteCount,
                    chunkContext.publicationInsertMs,
                    chunkContext.publicationUpdateMs,
                    chunkContext.publicationRecoverMs,
                    chunkContext.sourceLinkUpsertMs,
                    chunkContext.authorshipEdgeUpsertMs,
                    chunkContext.publicationAuthorAffiliationEdgeUpsertMs,
                    chunkContext.conflictSaveMs);
        }
        log.info("Scholardex publication canonicalization summary: processed={}, imported={}, updated={}, skipped={}, errors={}, batchesProcessed={}, totalBatches={}, resumedFromCheckpoint={}, checkpointLastCompletedBatch={}, totalMs={}",
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
                "publication",
                SOURCE_SCOPUS,
                result.getErrorCount() > 0 ? "failure" : "success",
                System.nanoTime() - startedAtNanos
        );
        return result;
    }

    private PublicationChunkExecution processChunk(
            List<ScopusPublicationFact> chunk,
            ImportProcessingResult result,
            int chunkNo,
            int totalBatches,
            int totalRecords
    ) {
        long chunkStartedAtNanos = System.nanoTime();
        ChunkContext context = preloadChunkContext(chunk);
        long preloadFinishedAtNanos = System.nanoTime();
        long resolveFinishedAtNanos = preloadFinishedAtNanos;
        long heartbeatIntervalNanos = Math.max(1L, heartbeatSeconds) * 1_000_000_000L;
        long lastHeartbeatAtNanos = chunkStartedAtNanos;
        int chunkProcessed = 0;
        for (ScopusPublicationFact scopusFact : chunk) {
            result.markProcessed();
            upsertFromScopusFact(scopusFact, result, context);
            chunkProcessed++;
            long now = System.nanoTime();
            if (now - lastHeartbeatAtNanos >= heartbeatIntervalNanos) {
                long elapsedMs = nanosToMillis(now - chunkStartedAtNanos);
                long ratePerSec = elapsedMs <= 0 ? 0 : Math.round((chunkProcessed * 1000.0d) / elapsedMs);
                log.info("Scholardex publication canonicalization heartbeat [batch={} / totalBatches={}]: chunkProcessed={} totalProcessed={} totalRecords={} elapsedMs={} ratePerSec={}",
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
        flushChunkContext(context);
        long upsertFinishedAtNanos = System.nanoTime();
        long saveFinishedAtNanos = upsertFinishedAtNanos;
        CanonicalBuildChunkTimings timings = new CanonicalBuildChunkTimings(
                nanosToMillis(preloadFinishedAtNanos - chunkStartedAtNanos),
                nanosToMillis(resolveFinishedAtNanos - preloadFinishedAtNanos),
                nanosToMillis(upsertFinishedAtNanos - resolveFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - upsertFinishedAtNanos),
                nanosToMillis(saveFinishedAtNanos - chunkStartedAtNanos)
        );
        return new PublicationChunkExecution(timings, context);
    }

    public void upsertFromScopusFact(ScopusPublicationFact scopusFact, ImportProcessingResult result) {
        ChunkContext context = new ChunkContext();
        upsertFromScopusFact(scopusFact, result, context);
        flushChunkContext(context);
    }

    private void upsertFromScopusFact(ScopusPublicationFact scopusFact, ImportProcessingResult result, ChunkContext context) {
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
        AuthorBridgeResult authorBridgeResult = bridgeAuthorIds(scopusFact.getAuthors(), scopusFact.getSource(), context);
        applyCanonicalPublicationFields(fact, scopusFact, authorBridgeResult, now);
        queuePublicationFact(context, fact, created);
        queueSourceLinkCommand(
                context,
                ScholardexEntityType.PUBLICATION,
                fact.getSource(),
                fact.getSourceRecordId(),
                fact.getId(),
                ScholardexSourceLinkService.STATE_LINKED,
                LINK_REASON_SCOPUS_BRIDGE,
                fact.getSourceEventId(),
                fact.getSourceBatchId(),
                fact.getSourceCorrelationId()
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

    public AuthorBridgeResult bridgeAuthorIds(List<String> sourceAuthorIds, String source) {
        return bridgeAuthorIds(sourceAuthorIds, source, new ChunkContext());
    }

    private AuthorBridgeResult bridgeAuthorIds(List<String> sourceAuthorIds, String source, ChunkContext context) {
        if (sourceAuthorIds == null || sourceAuthorIds.isEmpty()) {
            return new AuthorBridgeResult(List.of(), List.of(), List.of());
        }
        String sourceToken = normalizeToken(context, source);
        Map<String, AuthorBridgeEntry> byCanonicalId = new LinkedHashMap<>();
        LinkedHashSet<String> pendingSourceIds = new LinkedHashSet<>();
        for (String rawAuthorId : sourceAuthorIds) {
            String sourceAuthorId = normalizeBlank(context, rawAuthorId);
            if (sourceAuthorId == null) {
                continue;
            }
            Optional<ScholardexSourceLink> resolved = resolveAuthorSourceLink(source, sourceAuthorId, context);
            if (resolved.isPresent() && !isBlank(resolved.get().getCanonicalEntityId())) {
                String canonicalAuthorId = resolved.get().getCanonicalEntityId();
                byCanonicalId.putIfAbsent(canonicalAuthorId, new AuthorBridgeEntry(canonicalAuthorId, sourceAuthorId, false));
                continue;
            }
            String fallbackAuthorId = buildCanonicalAuthorFallbackId(sourceToken, sourceAuthorId);
            byCanonicalId.putIfAbsent(fallbackAuthorId, new AuthorBridgeEntry(fallbackAuthorId, sourceAuthorId, true));
            pendingSourceIds.add(sourceAuthorId);
            queueSourceLinkCommand(
                    context,
                    ScholardexEntityType.AUTHOR,
                    source,
                    sourceAuthorId,
                    fallbackAuthorId,
                    ScholardexSourceLinkService.STATE_UNMATCHED,
                    LINK_REASON_AUTHOR_FALLBACK,
                    null,
                    null,
                    null
            );
        }
        List<String> canonicalAuthorIds = byCanonicalId.keySet().stream().toList();
        List<AuthorBridgeEntry> entries = new ArrayList<>(byCanonicalId.values());
        return new AuthorBridgeResult(canonicalAuthorIds, new ArrayList<>(pendingSourceIds), entries);
    }

    private Optional<ScholardexSourceLink> resolveAuthorSourceLink(String source, String sourceAuthorId, ChunkContext context) {
        String normalizedSource = normalizeBlank(context, source);
        if (normalizedSource != null) {
            Optional<ScholardexSourceLink> direct = findSourceLink(ScholardexEntityType.AUTHOR, normalizedSource, sourceAuthorId, context);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return findSourceLink(ScholardexEntityType.AUTHOR, SOURCE_SCOPUS, sourceAuthorId, context);
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

    private void applyCanonicalPublicationFields(
            ScholardexPublicationFact fact,
            ScopusPublicationFact scopusFact,
            AuthorBridgeResult authorBridgeResult,
            Instant now
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
            authorEntriesBySourceId.put(normalizeToken(context, entry.sourceAuthorId()), entry);
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
                    buildAuthorshipSourceRecordId(context, fact.getSourceRecordId(), entry.sourceAuthorId()),
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
                String commandKey = normalizeToken(context, command.leftId()) + "|" + normalizeToken(context, command.rightId())
                        + "|" + normalizeToken(context, command.source()) + "|" + normalizeToken(context, command.sourceRecordId());
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
            String sourceAuthorId = normalizeBlank(context, sourceAuthorIds.get(i));
            if (sourceAuthorId == null) {
                continue;
            }
            AuthorBridgeEntry authorEntry = authorEntriesBySourceId.get(normalizeToken(context, sourceAuthorId));
            if (authorEntry == null || isBlank(authorEntry.canonicalAuthorId())) {
                openPublicationAuthorAffiliationConflict(context, fact, sourceAuthorId, null, REASON_PAPER_AFFILIATION_UNRESOLVED);
                continue;
            }
            List<String> sourceAffiliationIds = splitDash(context, sourceAuthorAffiliationIds.get(i));
            for (String sourceAffiliationId : sourceAffiliationIds) {
                String normalizedSourceAffiliationId = normalizeBlank(context, sourceAffiliationId);
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
                            context,
                            fact,
                            sourceAuthorId,
                            normalizedSourceAffiliationId,
                            REASON_PAPER_AFFILIATION_UNRESOLVED
                    );
                    continue;
                }
                String canonicalAffiliationId = resolvedAffiliation.get().getCanonicalEntityId();
                String dedupKey = normalizeToken(context, fact.getId())
                        + "|" + normalizeToken(context, authorEntry.canonicalAuthorId())
                        + "|" + normalizeToken(context, canonicalAffiliationId)
                        + "|" + normalizeToken(context, fact.getSource());
                if (!publicationAffiliationDedup.add(dedupKey)) {
                    continue;
                }
                publicationAffiliationCommands.add(new ScholardexEdgeWriterService.EdgeWriteCommand(
                        fact.getId(),
                        authorEntry.canonicalAuthorId(),
                        canonicalAffiliationId,
                        fact.getSource(),
                        buildPublicationAuthorAffiliationSourceRecordId(
                                context,
                                fact.getSourceRecordId(),
                                sourceAuthorId,
                                normalizedSourceAffiliationId
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
                String commandKey = normalizeToken(context, command.publicationId()) + "|" + normalizeToken(context, command.leftId())
                        + "|" + normalizeToken(context, command.rightId()) + "|" + normalizeToken(context, command.source())
                        + "|" + normalizeToken(context, command.sourceRecordId());
                context.pendingPublicationAuthorAffiliationCommands.put(commandKey, command);
            }
        }
    }

    public void syncAuthorshipEdges(ScholardexPublicationFact fact, AuthorBridgeResult bridge) {
        ChunkContext context = new ChunkContext();
        upsertPublicationEdges(fact, null, bridge, context);
        flushChunkContext(context);
    }

    private String buildAuthorshipSourceRecordId(String publicationSourceRecordId, String sourceAuthorId) {
        return normalizeToken(publicationSourceRecordId) + "::author::" + normalizeToken(sourceAuthorId);
    }

    private String buildAuthorshipSourceRecordId(ChunkContext context, String publicationSourceRecordId, String sourceAuthorId) {
        return normalizeToken(context, publicationSourceRecordId) + "::author::" + normalizeToken(context, sourceAuthorId);
    }

    private String buildPublicationAuthorAffiliationSourceRecordId(
            String publicationSourceRecordId,
            String sourceAuthorId,
            String sourceAffiliationId
    ) {
        return normalizeToken(publicationSourceRecordId)
                + "::author::" + normalizeToken(sourceAuthorId)
                + "::affiliation::" + normalizeToken(sourceAffiliationId);
    }

    private String buildPublicationAuthorAffiliationSourceRecordId(
            ChunkContext context,
            String publicationSourceRecordId,
            String sourceAuthorId,
            String sourceAffiliationId
    ) {
        return normalizeToken(context, publicationSourceRecordId)
                + "::author::" + normalizeToken(context, sourceAuthorId)
                + "::affiliation::" + normalizeToken(context, sourceAffiliationId);
    }

    private Optional<ScholardexSourceLink> resolveAffiliationSourceLink(
            String source,
            String sourceAffiliationId,
            ChunkContext context
    ) {
        String normalizedSource = normalizeBlank(context, source);
        if (normalizedSource != null) {
            Optional<ScholardexSourceLink> direct = findSourceLink(ScholardexEntityType.AFFILIATION, normalizedSource, sourceAffiliationId, context);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return findSourceLink(ScholardexEntityType.AFFILIATION, SOURCE_SCOPUS, sourceAffiliationId, context);
    }

    private void openPublicationAuthorAffiliationConflict(
            ChunkContext context,
            ScholardexPublicationFact fact,
            String sourceAuthorId,
            String sourceAffiliationId,
            String reasonCode
    ) {
        if (fact == null || isBlank(fact.getSource())) {
            return;
        }
        String sourceRecordId = buildPublicationAuthorAffiliationSourceRecordId(
                fact.getSourceRecordId(),
                sourceAuthorId,
                sourceAffiliationId == null ? "missing" : sourceAffiliationId
        );
        String normalizedSource = normalizeBlank(fact.getSource());
        String normalizedRecordId = normalizeBlank(sourceRecordId);
        if (normalizedSource == null || normalizedRecordId == null) {
            return;
        }
        String key = ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION.name()
                + "|" + normalizeToken(normalizedSource)
                + "|" + normalizeToken(normalizedRecordId)
                + "|" + normalizeToken(reasonCode);
        ScholardexIdentityConflict conflict = context.pendingIdentityConflicts.get(key);
        if (conflict == null) {
            conflict = new ScholardexIdentityConflict();
            conflict.setEntityType(ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION);
            conflict.setIncomingSource(normalizedSource);
            conflict.setIncomingSourceRecordId(normalizedRecordId);
            conflict.setReasonCode(reasonCode);
            conflict.setStatus(STATUS_OPEN);
            conflict.setCandidateCanonicalIds(List.of());
            conflict.setDetectedAt(Instant.now());
            context.pendingIdentityConflicts.put(key, conflict);
        }
        conflict.setSourceEventId(normalizeBlank(fact.getSourceEventId()));
        conflict.setSourceBatchId(normalizeBlank(fact.getSourceBatchId()));
        conflict.setSourceCorrelationId(normalizeBlank(fact.getSourceCorrelationId()));
    }

    private String buildCanonicalAuthorFallbackId(String sourceToken, String sourceAuthorId) {
        String normalizedSource = isBlank(sourceToken) ? "unknown" : sourceToken;
        return "sauth_" + shortHash("source|" + normalizedSource + "|author|" + normalizeToken(sourceAuthorId));
    }

    private ScholardexPublicationFact loadExistingByEidOrDoi(String eid, String doiNormalized, ImportProcessingResult result) {
        Optional<ScholardexPublicationFact> existingByEid = scholardexPublicationFactRepository.findByEid(eid);
        if (existingByEid.isPresent()) {
            return existingByEid.get();
        }
        return findSingleByDoi(doiNormalized, result).orElseGet(ScholardexPublicationFact::new);
    }

    private ScholardexPublicationFact loadExistingByEidOrDoi(
            String eid,
            String doiNormalized,
            ImportProcessingResult result,
            ChunkContext context
    ) {
        if (context == null || !context.preloaded) {
            return loadExistingByEidOrDoi(eid, doiNormalized, result);
        }
        String normalizedEid = normalizeBlank(eid);
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
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "" : normalized;
    }

    private List<String> splitDash(String value) {
        String normalized = normalizeBlank(value);
        if (normalized == null) {
            return List.of();
        }
        String[] tokens = normalized.split("-");
        List<String> values = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String trimmed = normalizeBlank(token);
            if (trimmed != null) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private List<String> splitDash(ChunkContext context, String value) {
        String normalized = normalizeBlank(context, value);
        if (normalized == null) {
            return List.of();
        }
        String[] tokens = normalized.split("-");
        List<String> values = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String trimmed = normalizeBlank(context, token);
            if (trimmed != null) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeToken(ChunkContext context, String value) {
        if (context == null) {
            return normalizeToken(value);
        }
        String key = value == null ? NULL_CACHE_KEY : value;
        return context.tokenNormalizationCache.computeIfAbsent(key, ignored -> normalizeToken(value));
    }

    private String normalizeBlank(ChunkContext context, String value) {
        if (context == null) {
            return normalizeBlank(value);
        }
        String key = value == null ? NULL_CACHE_KEY : value;
        if (context.blankNormalizationCache.containsKey(key)) {
            return context.blankNormalizationCache.get(key);
        }
        String normalized = normalizeBlank(value);
        context.blankNormalizationCache.put(key, normalized);
        return normalized;
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

    private String lastRecordKey(List<ScopusPublicationFact> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return null;
        }
        return normalizeBlank(chunk.get(chunk.size() - 1).getEid());
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private List<ScopusPublicationFact> loadSourceFacts(CanonicalBuildOptions options) {
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

    private ChunkContext preloadChunkContext(List<ScopusPublicationFact> chunk) {
        ChunkContext context = new ChunkContext();
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
                    for (String affiliationId : splitDash(authorAffiliation)) {
                        String normalized = normalizeBlank(affiliationId);
                        if (normalized != null) {
                            sourceAffiliationIds.add(normalized);
                        }
                    }
                }
            }
        }
        preloadSourceLinks(context, ScholardexEntityType.AUTHOR, sourceAuthorIds);
        preloadSourceLinks(context, ScholardexEntityType.AFFILIATION, sourceAffiliationIds);
        preloadSourceLinks(context, ScholardexEntityType.PUBLICATION, sourcePublicationIds);
        preloadPublicationFacts(context, eids, dois);
        context.preloaded = true;
        return context;
    }

    private void preloadPublicationFacts(ChunkContext context, Set<String> eids, Set<String> doiNormalizedValues) {
        if ((eids == null || eids.isEmpty()) && (doiNormalizedValues == null || doiNormalizedValues.isEmpty())) {
            return;
        }
        if (eids != null && !eids.isEmpty()) {
            for (ScholardexPublicationFact fact : scholardexPublicationFactRepository.findAllByEidIn(eids)) {
                indexPublicationFact(context, fact);
            }
        }
        if (doiNormalizedValues != null && !doiNormalizedValues.isEmpty()) {
            for (ScholardexPublicationFact fact : scholardexPublicationFactRepository.findAllByDoiNormalizedIn(doiNormalizedValues)) {
                indexPublicationFact(context, fact);
            }
        }
    }

    private void indexPublicationFact(ChunkContext context, ScholardexPublicationFact fact) {
        if (context == null || fact == null) {
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

    private void queuePublicationFact(ChunkContext context, ScholardexPublicationFact fact, boolean created) {
        if (context == null || fact == null || isBlank(fact.getId())) {
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

    private void preloadSourceLinks(ChunkContext context, ScholardexEntityType entityType, Set<String> sourceRecordIds) {
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

    private void queueSourceLinkCommand(
            ChunkContext context,
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalId,
            String state,
            String reason,
            String sourceEventId,
            String sourceBatchId,
            String sourceCorrelationId
    ) {
        if (context == null || entityType == null || isBlank(source) || isBlank(sourceRecordId)) {
            return;
        }
        String key = entityType.name() + "|" + normalizeToken(context, source) + "|" + normalizeToken(context, sourceRecordId);
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
                        Instant.now()
                );
                long startedAt = System.nanoTime();
                scholardexPublicationFactRepository.save(recovered);
                context.publicationRecoverMs += nanosToMillis(System.nanoTime() - startedAt);
                context.publicationRecoverCount++;
                queuePublicationFact(context, recovered, false);
            } else {
                throw duplicateKeyException;
            }
        }
    }

    private record PublicationChunkExecution(
            CanonicalBuildChunkTimings timings,
            ChunkContext context
    ) {}

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

    private static class ChunkContext {
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
