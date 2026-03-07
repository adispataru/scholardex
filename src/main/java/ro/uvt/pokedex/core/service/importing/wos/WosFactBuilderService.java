package ro.uvt.pokedex.core.service.importing.wos;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactBuildCheckpoint;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.model.IdentityResolutionResult;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentitySourceContext;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParserRunResult;

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

@Service
public class WosFactBuilderService {
    private static final Logger log = LoggerFactory.getLogger(WosFactBuilderService.class);
    private static final int FACT_BUILD_CHUNK_SIZE = 1_000;
    private static final int FACT_BUILD_HEARTBEAT_INTERVAL = 10_000;
    private static final int ENRICHMENT_GROUP_CHUNK_SIZE = 1_000;
    private static final String IDENTITY_NULL_MARKER = "__NULL__";

    private final WosImportEventParserOrchestrator parserOrchestrator;
    private final WosIdentityResolutionService identityResolutionService;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final WosFactConflictRepository factConflictRepository;
    private final MongoTemplate mongoTemplate;
    private final WosFactBuildCheckpointService checkpointService;
    private final Counter ifSourcePolicySkipCounter;

    public WosFactBuilderService(
            WosImportEventParserOrchestrator parserOrchestrator,
            WosIdentityResolutionService identityResolutionService,
            WosMetricFactRepository metricFactRepository,
            WosCategoryFactRepository categoryFactRepository,
            WosFactConflictRepository factConflictRepository,
            MongoTemplate mongoTemplate,
            WosFactBuildCheckpointService checkpointService,
            MeterRegistry meterRegistry
    ) {
        this.parserOrchestrator = parserOrchestrator;
        this.identityResolutionService = identityResolutionService;
        this.metricFactRepository = metricFactRepository;
        this.categoryFactRepository = categoryFactRepository;
        this.factConflictRepository = factConflictRepository;
        this.mongoTemplate = mongoTemplate;
        this.checkpointService = checkpointService;
        this.ifSourcePolicySkipCounter = meterRegistry.counter("pokedex.wos.if.source_policy.skips");
    }

    public ImportProcessingResult buildFactsFromImportEvents() {
        return buildFactsFromImportEventsWithCheckpoint(null, false, null, null).result();
    }

    public FactBuildRunResult buildFactsFromImportEventsWithCheckpoint(
            Integer startBatchOverride,
            boolean useCheckpoint,
            String runId,
            String sourceVersion
    ) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Map<String, String> identityCache = new HashMap<>();
        WosParserRunResult parserRun = parserOrchestrator.parseAllEvents();
        List<WosParsedRecord> records = parserRun.records();
        int total = records.size();
        int totalBatches = total == 0 ? 0 : ((total - 1) / FACT_BUILD_CHUNK_SIZE) + 1;
        Optional<WosFactBuildCheckpoint> checkpoint = useCheckpoint ? checkpointService.readCheckpoint() : Optional.empty();
        int checkpointLastCompletedBatch = checkpoint.map(WosFactBuildCheckpoint::getLastCompletedBatch).orElse(-1);
        int startBatch = normalizeStartBatch(startBatchOverride, checkpointLastCompletedBatch, useCheckpoint);
        boolean resumedFromCheckpoint = useCheckpoint && startBatchOverride == null && checkpointLastCompletedBatch >= 0;

        if (startBatch >= totalBatches) {
            return new FactBuildRunResult(
                    result,
                    startBatch,
                    startBatch - 1,
                    0,
                    resumedFromCheckpoint,
                    checkpointLastCompletedBatch
            );
        }

        int batchesProcessed = 0;
        int endBatch = startBatch - 1;
        for (int from = startBatch * FACT_BUILD_CHUNK_SIZE; from < total; from += FACT_BUILD_CHUNK_SIZE) {
            int to = Math.min(total, from + FACT_BUILD_CHUNK_SIZE);
            int batchIndex = from / FACT_BUILD_CHUNK_SIZE;
            processChunk(records.subList(from, to), result, identityCache, batchIndex + 1, batchIndex, totalBatches);
            batchesProcessed++;
            endBatch = batchIndex;
            if (useCheckpoint) {
                List<WosParsedRecord> chunk = records.subList(from, to);
                checkpointService.upsertCheckpoint(
                        batchIndex,
                        FACT_BUILD_CHUNK_SIZE,
                        lastRecordKey(chunk),
                        runId,
                        sourceVersion
                );
            }
        }

        log.info("WoS fact-builder summary: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                result.getSkippedCount(), result.getErrorCount(), result.getErrorsSample());
        return new FactBuildRunResult(
                result,
                startBatch,
                endBatch,
                batchesProcessed,
                resumedFromCheckpoint,
                checkpointLastCompletedBatch
        );
    }

    public Optional<WosFactBuildCheckpoint> readFactBuildCheckpoint() {
        return checkpointService.readCheckpoint();
    }

    public void resetFactBuildCheckpoint() {
        checkpointService.resetCheckpoint();
    }

    public ImportProcessingResult enrichMissingCategoryRankingFields() {
        long enrichmentStartedAtNanos = System.nanoTime();
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<WosCategoryFact> allCategoryFacts = categoryFactRepository.findAll();
        if (allCategoryFacts.isEmpty()) {
            return result;
        }

        Map<MetricFactKey, WosMetricFact> metricByKey = new LinkedHashMap<>();
        for (WosMetricFact metricFact : metricFactRepository.findAll()) {
            MetricFactKey key = new MetricFactKey(metricFact.getJournalId(), metricFact.getYear(), metricFact.getMetricType());
            metricByKey.put(key, metricFact);
        }

        Map<CategoryEnrichmentGroupKey, List<WosCategoryFact>> groups = new LinkedHashMap<>();
        for (WosCategoryFact fact : allCategoryFacts) {
            result.markProcessed();
            if (!requiresCategoryRankingEnrichment(fact)) {
                continue;
            }
            if (fact.getYear() == null
                    || fact.getMetricType() == null
                    || fact.getCategoryNameCanonical() == null
                    || fact.getCategoryNameCanonical().isBlank()
                    || fact.getEditionNormalized() == null) {
                result.markSkipped("insufficient-grouping-data factId=" + fact.getId());
                continue;
            }
            CategoryEnrichmentGroupKey key = new CategoryEnrichmentGroupKey(
                    fact.getYear(),
                    fact.getMetricType(),
                    fact.getCategoryNameCanonical(),
                    fact.getEditionNormalized()
            );
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact);
        }

        List<Map.Entry<CategoryEnrichmentGroupKey, List<WosCategoryFact>>> groupEntries = new ArrayList<>(groups.entrySet());
        int totalGroups = groupEntries.size();
        int totalBatches = totalGroups == 0 ? 0 : ((totalGroups - 1) / ENRICHMENT_GROUP_CHUNK_SIZE) + 1;
        int batchesProcessed = 0;

        for (int from = 0; from < totalGroups; from += ENRICHMENT_GROUP_CHUNK_SIZE) {
            long chunkStartedAtNanos = System.nanoTime();
            int to = Math.min(totalGroups, from + ENRICHMENT_GROUP_CHUNK_SIZE);
            int batchIndex = from / ENRICHMENT_GROUP_CHUNK_SIZE;
            int chunkNo = batchIndex + 1;

            long enrichStartedAtNanos = System.nanoTime();
            List<WosCategoryFact> pendingUpdates = new ArrayList<>();
            for (int i = from; i < to; i++) {
                enrichCategoryGroup(groupEntries.get(i).getValue(), metricByKey, pendingUpdates, result);
            }

            long saveStartedAtNanos = System.nanoTime();
            if (!pendingUpdates.isEmpty()) {
                categoryFactRepository.saveAll(pendingUpdates);
            }
            long finishedAtNanos = System.nanoTime();
            batchesProcessed++;

            log.info("WoS category enrichment chunk {} complete [batch={} / totalBatches={}]: groups={} updates={} timingsMs[enrich={}, save={}, total={}]",
                    chunkNo,
                    chunkNo,
                    totalBatches,
                    (to - from),
                    pendingUpdates.size(),
                    nanosToMillis(saveStartedAtNanos - enrichStartedAtNanos),
                    nanosToMillis(finishedAtNanos - saveStartedAtNanos),
                    nanosToMillis(finishedAtNanos - chunkStartedAtNanos));
        }

        long enrichmentFinishedAtNanos = System.nanoTime();
        log.info("WoS category ranking enrichment summary: processed={}, updated={}, skipped={}, errors={}, groups={}, chunksProcessed={}, totalChunks={}, totalMs={}",
                result.getProcessedCount(),
                result.getUpdatedCount(),
                result.getSkippedCount(),
                result.getErrorCount(),
                totalGroups,
                batchesProcessed,
                totalBatches,
                nanosToMillis(enrichmentFinishedAtNanos - enrichmentStartedAtNanos));
        return result;
    }

    private void processChunk(
            List<WosParsedRecord> chunk,
            ImportProcessingResult result,
            Map<String, String> identityCache,
            int chunkNo,
            int batchIndex,
            int totalBatches
    ) {
        long chunkStartedAtNanos = System.nanoTime();
        List<ResolvedRecord> resolved = new ArrayList<>(chunk.size());
        Map<String, String> preResolvedIdentityByKey = preResolveIdentityByKey(chunk);
        int ifPolicySkipped = 0;
        List<String> ifPolicySkipSamples = new ArrayList<>();
        for (WosParsedRecord record : chunk) {
            result.markProcessed();
            if (result.getProcessedCount() % FACT_BUILD_HEARTBEAT_INTERVAL == 0) {
                log.info("WoS fact-builder progress: processed={} imported={} updated={} skipped={} errors= {}",
                        result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                        result.getSkippedCount(), result.getErrorCount());
            }
            if (!WosCanonicalContractSupport.isSourceAllowedForMetric(record.metricType(), record.sourceType())) {
                if (record.metricType() == MetricType.IF) {
                    ifSourcePolicySkipCounter.increment();
                    ifPolicySkipped++;
                    if (ifPolicySkipSamples.size() < 5) {
                        ifPolicySkipSamples.add(record.sourceFile() + "#" + record.sourceRowItem());
                    }
                }
                result.markSkipped("source-not-allowed metric=" + record.metricType() + ", source=" + record.sourceType()
                        + ", sourceRef=" + record.sourceFile() + "#" + record.sourceRowItem());
                continue;
            }
            String journalId = resolveJournalId(record, result, identityCache, preResolvedIdentityByKey);
            if (journalId == null || journalId.isBlank()) {
                continue;
            }
            resolved.add(new ResolvedRecord(journalId, record));
        }

        long preloadStartedAtNanos = System.nanoTime();
        Map<MetricFactKey, WosMetricFact> existingMetrics = preloadMetricFacts(resolved);
        Map<CategoryFactKey, WosCategoryFact> existingCategories = preloadCategoryFacts(resolved);

        long upsertStartedAtNanos = System.nanoTime();
        Map<MetricFactKey, WosMetricFact> pendingMetricSaves = new LinkedHashMap<>();
        Map<CategoryFactKey, WosCategoryFact> pendingCategorySaves = new LinkedHashMap<>();
        List<WosFactConflict> pendingConflicts = new ArrayList<>();

        for (ResolvedRecord resolvedRecord : resolved) {
            upsertMetricFact(resolvedRecord, result, existingMetrics, pendingMetricSaves, pendingConflicts);
            upsertCategoryFact(resolvedRecord, result, existingCategories, pendingCategorySaves, pendingConflicts);
        }

        long saveStartedAtNanos = System.nanoTime();
        if (!pendingMetricSaves.isEmpty()) {
            metricFactRepository.saveAll(pendingMetricSaves.values());
        }
        if (!pendingCategorySaves.isEmpty()) {
            categoryFactRepository.saveAll(pendingCategorySaves.values());
        }
        if (!pendingConflicts.isEmpty()) {
            factConflictRepository.saveAll(pendingConflicts);
        }

        if (ifPolicySkipped > 0) {
            log.warn("WoS IF source-policy skips in chunk {} (batch {} of {}): skipped={}, samples={}",
                    chunkNo, batchIndex, totalBatches, ifPolicySkipped, ifPolicySkipSamples);
        }

        long finishedAtNanos = System.nanoTime();
        log.info("WoS fact-builder chunk {} complete [batch={} / totalBatches={}]: resolved={} metricWrites={} categoryWrites={} conflictWrites={} timingsMs[identity+filter={}, preload={}, upsert={}, save={}, total={}]",
                chunkNo,
                batchIndex,
                totalBatches,
                resolved.size(),
                pendingMetricSaves.size(),
                pendingCategorySaves.size(),
                pendingConflicts.size(),
                nanosToMillis(preloadStartedAtNanos - chunkStartedAtNanos),
                nanosToMillis(upsertStartedAtNanos - preloadStartedAtNanos),
                nanosToMillis(saveStartedAtNanos - upsertStartedAtNanos),
                nanosToMillis(finishedAtNanos - saveStartedAtNanos),
                nanosToMillis(finishedAtNanos - chunkStartedAtNanos));
    }

    private Map<MetricFactKey, WosMetricFact> preloadMetricFacts(List<ResolvedRecord> resolved) {
        Set<MetricFactKey> keys = new LinkedHashSet<>();
        for (ResolvedRecord rr : resolved) {
            MetricFactKey key = metricKey(rr.journalId(), rr.record());
            if (key != null) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<MetricPreloadGroup, Set<String>> groupToJournalIds = new LinkedHashMap<>();
        for (MetricFactKey key : keys) {
            MetricPreloadGroup group = new MetricPreloadGroup(key.year(), key.metricType());
            groupToJournalIds.computeIfAbsent(group, ignored -> new LinkedHashSet<>()).add(key.journalId());
        }

        List<WosMetricFact> existing = new ArrayList<>();
        for (Map.Entry<MetricPreloadGroup, Set<String>> entry : groupToJournalIds.entrySet()) {
            MetricPreloadGroup group = entry.getKey();
            Query query = new Query(new Criteria().andOperator(
                    Criteria.where("year").is(group.year()),
                    Criteria.where("metricType").is(group.metricType()),
                    Criteria.where("journalId").in(entry.getValue())
            ));
            existing.addAll(mongoTemplate.find(query, WosMetricFact.class));
        }

        Map<MetricFactKey, WosMetricFact> out = new LinkedHashMap<>();
        for (WosMetricFact fact : existing) {
            MetricFactKey key = new MetricFactKey(fact.getJournalId(), fact.getYear(), fact.getMetricType());
            out.put(key, fact);
        }
        return out;
    }

    private Map<CategoryFactKey, WosCategoryFact> preloadCategoryFacts(List<ResolvedRecord> resolved) {
        Set<CategoryFactKey> keys = new LinkedHashSet<>();
        for (ResolvedRecord rr : resolved) {
            CategoryFactKey key = categoryKey(rr.journalId(), rr.record());
            if (key != null) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<CategoryPreloadGroup, Set<String>> groupToJournalIds = new LinkedHashMap<>();
        for (CategoryFactKey key : keys) {
            CategoryPreloadGroup group = new CategoryPreloadGroup(
                    key.year(),
                    key.metricType(),
                    key.categoryNameCanonical(),
                    key.editionNormalized()
            );
            groupToJournalIds.computeIfAbsent(group, ignored -> new LinkedHashSet<>()).add(key.journalId());
        }

        List<WosCategoryFact> existing = new ArrayList<>();
        for (Map.Entry<CategoryPreloadGroup, Set<String>> entry : groupToJournalIds.entrySet()) {
            CategoryPreloadGroup group = entry.getKey();
            Query query = new Query(new Criteria().andOperator(
                    Criteria.where("year").is(group.year()),
                    Criteria.where("metricType").is(group.metricType()),
                    Criteria.where("categoryNameCanonical").is(group.categoryNameCanonical()),
                    Criteria.where("editionNormalized").is(group.editionNormalized()),
                    Criteria.where("journalId").in(entry.getValue())
            ));
            existing.addAll(mongoTemplate.find(query, WosCategoryFact.class));
        }

        Map<CategoryFactKey, WosCategoryFact> out = new LinkedHashMap<>();
        for (WosCategoryFact fact : existing) {
            CategoryFactKey key = new CategoryFactKey(
                    fact.getJournalId(),
                    fact.getYear(),
                    fact.getMetricType(),
                    fact.getCategoryNameCanonical(),
                    fact.getEditionNormalized()
            );
            out.put(key, fact);
        }
        return out;
    }

    private Map<String, String> preResolveIdentityByKey(List<WosParsedRecord> chunk) {
        Set<String> identityKeys = new LinkedHashSet<>();
        for (WosParsedRecord record : chunk) {
            String identityKey = buildIdentityKey(record);
            if (identityKey != null && !identityKey.isBlank()) {
                identityKeys.add(identityKey);
            }
        }
        return identityResolutionService.findJournalIdsByIdentityKeys(identityKeys);
    }

    private String resolveJournalId(
            WosParsedRecord record,
            ImportProcessingResult result,
            Map<String, String> identityCache,
            Map<String, String> preResolvedIdentityByKey
    ) {
        if (!hasIssnIdentityTokens(record)) {
            result.markSkipped("identity-missing-no-issn source=" + record.sourceFile() + "#" + record.sourceRowItem());
            return null;
        }
        String cacheKey = identityCacheKey(record);
        if (identityCache.containsKey(cacheKey)) {
            String cached = identityCache.get(cacheKey);
            return IDENTITY_NULL_MARKER.equals(cached) ? null : cached;
        }

        String identityKey = buildIdentityKey(record);
        if (identityKey != null) {
            String preResolvedJournalId = preResolvedIdentityByKey.get(identityKey);
            if (preResolvedJournalId != null && !preResolvedJournalId.isBlank()) {
                identityCache.put(cacheKey, preResolvedJournalId);
                return preResolvedJournalId;
            }
        }

        try {
            IdentityResolutionResult identity = identityResolutionService.resolveIdentity(
                    record.issn(),
                    record.eIssn(),
                    record.title(),
                    new WosIdentitySourceContext(
                            record.year(),
                            record.editionRaw(),
                            record.sourceEventId(),
                            record.sourceFile(),
                            record.sourceVersion(),
                            record.sourceRowItem()
                    )
            );
            if (identity == null || identity.journalId() == null) {
                result.markSkipped("identity-missing source=" + record.sourceFile() + "#" + record.sourceRowItem());
                identityCache.put(cacheKey, IDENTITY_NULL_MARKER);
                return null;
            }
            identityCache.put(cacheKey, identity.journalId());
            return identity.journalId();
        } catch (Exception e) {
            result.markError("identity-error source=" + record.sourceFile() + "#" + record.sourceRowItem() + " " + e.getMessage());
            identityCache.put(cacheKey, IDENTITY_NULL_MARKER);
            return null;
        }
    }

    private void upsertMetricFact(
            ResolvedRecord resolved,
            ImportProcessingResult result,
            Map<MetricFactKey, WosMetricFact> existingMetrics,
            Map<MetricFactKey, WosMetricFact> pendingMetricSaves,
            List<WosFactConflict> pendingConflicts
    ) {
        WosParsedRecord record = resolved.record();
        MetricFactKey key = metricKey(resolved.journalId(), record);
        if (key == null) {
            result.markSkipped("metric-score-key-incomplete source=" + record.sourceFile() + "#" + record.sourceRowItem());
            return;
        }

        WosMetricFact existing = existingMetrics.get(key);
        if (existing == null) {
            WosMetricFact created = toMetricFact(resolved.journalId(), record);
            existingMetrics.put(key, created);
            pendingMetricSaves.put(key, created);
            result.markImported();
            return;
        }

        if (isSameSourceFile(existing, record)) {
            if (safeEq(existing.getValue(), record.value())) {
                result.markSkipped("metric-score-duplicate-same-file key=" + metricScoreKeyString(resolved.journalId(), record));
                return;
            }
            result.markSkipped("metric-score-conflict-same-file key=" + metricScoreKeyString(resolved.journalId(), record));
            return;
        }

        if (safeEq(existing.getValue(), record.value())) {
            result.markSkipped("metric-score-duplicate key=" + metricScoreKeyString(resolved.journalId(), record));
            return;
        }

        if (isZero(record.value())) {
            result.markSkipped("metric-score-zero-incoming key=" + metricScoreKeyString(resolved.journalId(), record));
            return;
        }

        WinnerDecision<WosMetricFact> decision = decideMetricWinner(existing, record);
        if (decision.incomingWins()) {
            WosMetricFact loserSnapshot = copyMetric(existing);
            WosMetricFact updated = applyMetricRecord(existing, record);
            pendingMetricSaves.put(key, updated);
            if (shouldEmitConflict(decision.reason())) {
                pendingConflicts.add(buildMetricConflictIncomingWinner(
                        decision.reason(),
                        metricScoreKeyString(resolved.journalId(), record),
                        record,
                        loserSnapshot
                ));
            }
            result.markUpdated();
        } else {
            if (shouldEmitConflict(decision.reason())) {
                pendingConflicts.add(buildMetricConflictExistingWinner(
                        decision.reason(),
                        metricScoreKeyString(resolved.journalId(), record),
                        existing,
                        record
                ));
            }
            result.markSkipped("metric-score-conflict-loser key=" + metricScoreKeyString(resolved.journalId(), record));
        }
    }

    private void upsertCategoryFact(
            ResolvedRecord resolved,
            ImportProcessingResult result,
            Map<CategoryFactKey, WosCategoryFact> existingCategories,
            Map<CategoryFactKey, WosCategoryFact> pendingCategorySaves,
            List<WosFactConflict> pendingConflicts
    ) {
        WosParsedRecord record = resolved.record();
        CategoryFactKey key = categoryKey(resolved.journalId(), record);
        if (key == null) {
            result.markSkipped("category-ranking-key-incomplete source=" + record.sourceFile() + "#" + record.sourceRowItem());
            return;
        }

        WosCategoryFact existing = existingCategories.get(key);
        if (existing == null) {
            WosCategoryFact created = toCategoryFact(resolved.journalId(), record);
            existingCategories.put(key, created);
            pendingCategorySaves.put(key, created);
            result.markImported();
            return;
        }

        if (shouldEnrichMissingCategoryRanking(existing, record)) {
            WosCategoryFact updated = applyCategoryRecord(existing, record);
            pendingCategorySaves.put(key, updated);
            result.markUpdated();
            return;
        }

        if (isSameCategoryRanking(existing, record)) {
            result.markSkipped("category-ranking-duplicate key=" + categoryRankingKeyString(resolved.journalId(), record));
            return;
        }

        WinnerDecision<WosCategoryFact> decision = decideCategoryWinner(existing, record);
        if (decision.incomingWins()) {
            WosCategoryFact loserSnapshot = copyCategory(existing);
            WosCategoryFact updated = applyCategoryRecord(existing, record);
            pendingCategorySaves.put(key, updated);
            if (shouldEmitConflict(decision.reason())) {
                pendingConflicts.add(buildCategoryConflictIncomingWinner(
                        decision.reason(),
                        categoryRankingKeyString(resolved.journalId(), record),
                        record,
                        loserSnapshot
                ));
            }
            result.markUpdated();
        } else {
            if (shouldEmitConflict(decision.reason())) {
                pendingConflicts.add(buildCategoryConflictExistingWinner(
                        decision.reason(),
                        categoryRankingKeyString(resolved.journalId(), record),
                        existing,
                        record
                ));
            }
            result.markSkipped("category-ranking-conflict-loser key=" + categoryRankingKeyString(resolved.journalId(), record));
        }
    }

    private int normalizeStartBatch(Integer startBatchOverride, int checkpointLastCompletedBatch, boolean useCheckpoint) {
        if (startBatchOverride != null) {
            return Math.max(0, startBatchOverride);
        }
        if (useCheckpoint) {
            return Math.max(0, checkpointLastCompletedBatch + 1);
        }
        return 0;
    }

    private String lastRecordKey(List<WosParsedRecord> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return null;
        }
        WosParsedRecord last = chunk.getLast();
        return String.join("|",
                last.sourceType() == null ? "null" : last.sourceType().name(),
                last.sourceFile() == null ? "" : last.sourceFile(),
                last.sourceVersion() == null ? "" : last.sourceVersion(),
                last.sourceRowItem() == null ? "" : last.sourceRowItem()
        );
    }

    private MetricFactKey metricKey(String journalId, WosParsedRecord record) {
        if (record.metricType() == null || record.year() == null) {
            return null;
        }
        return new MetricFactKey(journalId, record.year(), record.metricType());
    }

    private CategoryFactKey categoryKey(String journalId, WosParsedRecord record) {
        if (record.metricType() == null || record.year() == null || record.editionNormalized() == null) {
            return null;
        }
        if (record.categoryNameCanonical() == null || record.categoryNameCanonical().isBlank()) {
            return null;
        }
        return new CategoryFactKey(
                journalId,
                record.year(),
                record.metricType(),
                record.categoryNameCanonical(),
                record.editionNormalized()
        );
    }

    private WinnerDecision<WosMetricFact> decideMetricWinner(WosMetricFact existing, WosParsedRecord incoming) {
        WosSourceType preferred = WosCanonicalContractSupport.selectCanonicalOperationalSource(
                incoming.metricType(), existing.getSourceType(), incoming.sourceType());
        if (preferred == incoming.sourceType() && preferred != existing.getSourceType()) {
            return WinnerDecision.incoming("source-precedence");
        }
        if (preferred == existing.getSourceType() && preferred != incoming.sourceType()) {
            return WinnerDecision.existing("source-precedence");
        }
        int cmp = compareLineage(existing.getSourceVersion(), existing.getSourceRowItem(), incoming.sourceVersion(), incoming.sourceRowItem());
        if (cmp < 0) {
            return WinnerDecision.incoming("latest-lineage");
        }
        return WinnerDecision.existing("latest-lineage");
    }

    private WinnerDecision<WosCategoryFact> decideCategoryWinner(WosCategoryFact existing, WosParsedRecord incoming) {
        WosSourceType preferred = WosCanonicalContractSupport.selectCanonicalOperationalSource(
                incoming.metricType(), existing.getSourceType(), incoming.sourceType());
        if (preferred == incoming.sourceType() && preferred != existing.getSourceType()) {
            return WinnerDecision.incoming("source-precedence");
        }
        if (preferred == existing.getSourceType() && preferred != incoming.sourceType()) {
            return WinnerDecision.existing("source-precedence");
        }
        int cmp = compareLineage(existing.getSourceVersion(), existing.getSourceRowItem(), incoming.sourceVersion(), incoming.sourceRowItem());
        if (cmp < 0) {
            return WinnerDecision.incoming("latest-lineage");
        }
        return WinnerDecision.existing("latest-lineage");
    }

    private int compareLineage(String existingVersion, String existingRowItem, String incomingVersion, String incomingRowItem) {
        Comparator<String> cmp = Comparator.nullsFirst(String::compareTo);
        int byVersion = cmp.compare(existingVersion, incomingVersion);
        if (byVersion != 0) {
            return byVersion;
        }
        return compareRowItem(existingRowItem, incomingRowItem);
    }

    private int compareRowItem(String existing, String incoming) {
        Integer e = parseInt(existing);
        Integer i = parseInt(incoming);
        if (e != null && i != null) {
            return Integer.compare(e, i);
        }
        return Comparator.nullsFirst(String::compareTo).compare(existing, incoming);
    }

    private Integer parseInt(String raw) {
        try {
            return raw == null ? null : Integer.parseInt(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean safeEq(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean isSameSourceFile(WosMetricFact fact, WosParsedRecord record) {
        return safeEq(fact.getSourceFile(), record.sourceFile())
                && safeEq(fact.getSourceVersion(), record.sourceVersion());
    }

    private boolean isZero(Double value) {
        return value != null && Double.compare(value, 0.0d) == 0;
    }

    private boolean shouldEmitConflict(String reason) {
        return !"source-precedence".equals(reason) && !"duplicate-source-file".equals(reason);
    }

    private boolean isSameCategoryRanking(WosCategoryFact fact, WosParsedRecord record) {
        return safeEq(fact.getQuarter(), record.quarter())
                && safeEq(fact.getQuartileRank(), record.quartileRank())
                && safeEq(fact.getRank(), record.rank());
    }

    private boolean shouldEnrichMissingCategoryRanking(WosCategoryFact existing, WosParsedRecord incoming) {
        boolean existingMissingRanking = isBlank(existing.getQuarter())
                && existing.getQuartileRank() == null
                && existing.getRank() == null;
        boolean incomingHasRanking = !isBlank(incoming.quarter())
                || incoming.quartileRank() != null
                || incoming.rank() != null;
        return existingMissingRanking && incomingHasRanking;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private WosMetricFact toMetricFact(String journalId, WosParsedRecord record) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId(journalId);
        fact.setYear(record.year());
        fact.setMetricType(record.metricType());
        fact.setValue(record.value());
        fact.setSourceType(record.sourceType());
        fact.setSourceEventId(record.sourceEventId());
        fact.setSourceFile(record.sourceFile());
        fact.setSourceVersion(record.sourceVersion());
        fact.setSourceRowItem(record.sourceRowItem());
        fact.setCreatedAt(Instant.now());
        return fact;
    }

    private WosMetricFact applyMetricRecord(WosMetricFact fact, WosParsedRecord record) {
        fact.setValue(record.value());
        fact.setSourceType(record.sourceType());
        fact.setSourceEventId(record.sourceEventId());
        fact.setSourceFile(record.sourceFile());
        fact.setSourceVersion(record.sourceVersion());
        fact.setSourceRowItem(record.sourceRowItem());
        fact.setCreatedAt(Instant.now());
        return fact;
    }

    private WosCategoryFact toCategoryFact(String journalId, WosParsedRecord record) {
        WosCategoryFact fact = new WosCategoryFact();
        fact.setJournalId(journalId);
        fact.setYear(record.year());
        fact.setMetricType(record.metricType());
        fact.setCategoryNameCanonical(record.categoryNameCanonical());
        fact.setEditionRaw(record.editionRaw());
        fact.setEditionNormalized(record.editionNormalized());
        fact.setQuarter(record.quarter());
        fact.setQuartileRank(record.quartileRank());
        fact.setRank(record.rank());
        fact.setSourceType(record.sourceType());
        fact.setSourceEventId(record.sourceEventId());
        fact.setSourceFile(record.sourceFile());
        fact.setSourceVersion(record.sourceVersion());
        fact.setSourceRowItem(record.sourceRowItem());
        fact.setCreatedAt(Instant.now());
        return fact;
    }

    private WosCategoryFact applyCategoryRecord(WosCategoryFact fact, WosParsedRecord record) {
        fact.setEditionRaw(record.editionRaw());
        fact.setQuarter(record.quarter());
        fact.setQuartileRank(record.quartileRank());
        fact.setRank(record.rank());
        fact.setSourceType(record.sourceType());
        fact.setSourceEventId(record.sourceEventId());
        fact.setSourceFile(record.sourceFile());
        fact.setSourceVersion(record.sourceVersion());
        fact.setSourceRowItem(record.sourceRowItem());
        fact.setCreatedAt(Instant.now());
        return fact;
    }

    private WosFactConflict buildMetricConflictExistingWinner(String reason, String factKey, WosMetricFact winner, WosParsedRecord loser) {
        WosFactConflict conflict = new WosFactConflict();
        conflict.setFactType("METRIC_SCORE");
        conflict.setConflictReason(reason);
        conflict.setFactKey(factKey);
        conflict.setWinnerSourceEventId(winner.getSourceEventId());
        conflict.setWinnerSourceFile(winner.getSourceFile());
        conflict.setWinnerSourceVersion(winner.getSourceVersion());
        conflict.setWinnerSourceRowItem(winner.getSourceRowItem());
        conflict.setWinnerSourceType(winner.getSourceType());
        conflict.setWinnerValueSnapshot(String.valueOf(winner.getValue()));
        conflict.setLoserSourceEventId(loser.sourceEventId());
        conflict.setLoserSourceFile(loser.sourceFile());
        conflict.setLoserSourceVersion(loser.sourceVersion());
        conflict.setLoserSourceRowItem(loser.sourceRowItem());
        conflict.setLoserSourceType(loser.sourceType());
        conflict.setLoserValueSnapshot(String.valueOf(loser.value()));
        conflict.setDetectedAt(Instant.now());
        return conflict;
    }

    private WosFactConflict buildMetricConflictIncomingWinner(String reason, String factKey, WosParsedRecord winner, WosMetricFact loser) {
        WosFactConflict conflict = new WosFactConflict();
        conflict.setFactType("METRIC_SCORE");
        conflict.setConflictReason(reason);
        conflict.setFactKey(factKey);
        conflict.setWinnerSourceEventId(winner.sourceEventId());
        conflict.setWinnerSourceFile(winner.sourceFile());
        conflict.setWinnerSourceVersion(winner.sourceVersion());
        conflict.setWinnerSourceRowItem(winner.sourceRowItem());
        conflict.setWinnerSourceType(winner.sourceType());
        conflict.setWinnerValueSnapshot(String.valueOf(winner.value()));
        conflict.setLoserSourceEventId(loser.getSourceEventId());
        conflict.setLoserSourceFile(loser.getSourceFile());
        conflict.setLoserSourceVersion(loser.getSourceVersion());
        conflict.setLoserSourceRowItem(loser.getSourceRowItem());
        conflict.setLoserSourceType(loser.getSourceType());
        conflict.setLoserValueSnapshot(String.valueOf(loser.getValue()));
        conflict.setDetectedAt(Instant.now());
        return conflict;
    }

    private WosFactConflict buildCategoryConflictExistingWinner(String reason, String factKey, WosCategoryFact winner, WosParsedRecord loser) {
        WosFactConflict conflict = new WosFactConflict();
        conflict.setFactType("CATEGORY_RANKING");
        conflict.setConflictReason(reason);
        conflict.setFactKey(factKey);
        conflict.setWinnerSourceEventId(winner.getSourceEventId());
        conflict.setWinnerSourceFile(winner.getSourceFile());
        conflict.setWinnerSourceVersion(winner.getSourceVersion());
        conflict.setWinnerSourceRowItem(winner.getSourceRowItem());
        conflict.setWinnerSourceType(winner.getSourceType());
        conflict.setWinnerValueSnapshot(snapshotCategory(winner));
        conflict.setLoserSourceEventId(loser.sourceEventId());
        conflict.setLoserSourceFile(loser.sourceFile());
        conflict.setLoserSourceVersion(loser.sourceVersion());
        conflict.setLoserSourceRowItem(loser.sourceRowItem());
        conflict.setLoserSourceType(loser.sourceType());
        conflict.setLoserValueSnapshot(snapshotCategory(loser));
        conflict.setDetectedAt(Instant.now());
        return conflict;
    }

    private WosFactConflict buildCategoryConflictIncomingWinner(String reason, String factKey, WosParsedRecord winner, WosCategoryFact loser) {
        WosFactConflict conflict = new WosFactConflict();
        conflict.setFactType("CATEGORY_RANKING");
        conflict.setConflictReason(reason);
        conflict.setFactKey(factKey);
        conflict.setWinnerSourceEventId(winner.sourceEventId());
        conflict.setWinnerSourceFile(winner.sourceFile());
        conflict.setWinnerSourceVersion(winner.sourceVersion());
        conflict.setWinnerSourceRowItem(winner.sourceRowItem());
        conflict.setWinnerSourceType(winner.sourceType());
        conflict.setWinnerValueSnapshot(snapshotCategory(winner));
        conflict.setLoserSourceEventId(loser.getSourceEventId());
        conflict.setLoserSourceFile(loser.getSourceFile());
        conflict.setLoserSourceVersion(loser.getSourceVersion());
        conflict.setLoserSourceRowItem(loser.getSourceRowItem());
        conflict.setLoserSourceType(loser.getSourceType());
        conflict.setLoserValueSnapshot(snapshotCategory(loser));
        conflict.setDetectedAt(Instant.now());
        return conflict;
    }

    private String snapshotCategory(WosCategoryFact fact) {
        return (fact.getCategoryNameCanonical() == null ? "" : fact.getCategoryNameCanonical())
                + "|" + (fact.getEditionNormalized() == null ? "" : fact.getEditionNormalized())
                + "|" + (fact.getQuarter() == null ? "" : fact.getQuarter())
                + "|" + fact.getQuartileRank()
                + "|" + fact.getRank();
    }

    private String snapshotCategory(WosParsedRecord record) {
        return (record.categoryNameCanonical() == null ? "" : record.categoryNameCanonical())
                + "|" + (record.editionNormalized() == null ? "" : record.editionNormalized())
                + "|" + (record.quarter() == null ? "" : record.quarter())
                + "|" + record.quartileRank()
                + "|" + record.rank();
    }

    private String metricScoreKeyString(String journalId, WosParsedRecord record) {
        return "metric-score|" + journalId + "|" + record.year() + "|" + record.metricType();
    }

    private String categoryRankingKeyString(String journalId, WosParsedRecord record) {
        return "category-ranking|"
                + journalId + "|"
                + record.year() + "|"
                + record.metricType() + "|"
                + record.categoryNameCanonical() + "|"
                + record.editionNormalized();
    }

    private WosMetricFact copyMetric(WosMetricFact source) {
        WosMetricFact copy = new WosMetricFact();
        copy.setSourceEventId(source.getSourceEventId());
        copy.setSourceFile(source.getSourceFile());
        copy.setSourceVersion(source.getSourceVersion());
        copy.setSourceRowItem(source.getSourceRowItem());
        copy.setSourceType(source.getSourceType());
        copy.setValue(source.getValue());
        return copy;
    }

    private WosCategoryFact copyCategory(WosCategoryFact source) {
        WosCategoryFact copy = new WosCategoryFact();
        copy.setSourceEventId(source.getSourceEventId());
        copy.setSourceFile(source.getSourceFile());
        copy.setSourceVersion(source.getSourceVersion());
        copy.setSourceRowItem(source.getSourceRowItem());
        copy.setSourceType(source.getSourceType());
        copy.setCategoryNameCanonical(source.getCategoryNameCanonical());
        copy.setEditionNormalized(source.getEditionNormalized());
        copy.setQuarter(source.getQuarter());
        copy.setQuartileRank(source.getQuartileRank());
        copy.setRank(source.getRank());
        return copy;
    }

    private String identityCacheKey(WosParsedRecord record) {
        return normalizeIdentityPart(record.issn())
                + "|" + normalizeIdentityPart(record.eIssn());
    }

    private String buildIdentityKey(WosParsedRecord record) {
        Set<String> normalizedIssnTokens = new LinkedHashSet<>();
        String issn = WosCanonicalContractSupport.normalizeIssnToken(record.issn());
        String eIssn = WosCanonicalContractSupport.normalizeIssnToken(record.eIssn());
        if (issn != null) {
            normalizedIssnTokens.add(issn);
        }
        if (eIssn != null) {
            normalizedIssnTokens.add(eIssn);
        }
        if (normalizedIssnTokens.isEmpty()) {
            return null;
        }
        return WosCanonicalContractSupport.buildIdentityKey(
                normalizedIssnTokens,
                null,
                record.year(),
                record.editionRaw()
        );
    }

    private boolean hasIssnIdentityTokens(WosParsedRecord record) {
        return WosCanonicalContractSupport.normalizeIssnToken(record.issn()) != null
                || WosCanonicalContractSupport.normalizeIssnToken(record.eIssn()) != null;
    }

    private String normalizeIdentityPart(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private void enrichCategoryGroup(
            List<WosCategoryFact> groupFacts,
            Map<MetricFactKey, WosMetricFact> metricByKey,
            List<WosCategoryFact> pendingUpdates,
            ImportProcessingResult result
    ) {
        List<RankCandidate> ranked = new ArrayList<>();
        List<WosCategoryFact> uncomputable = new ArrayList<>();
        for (WosCategoryFact fact : groupFacts) {
            MetricFactKey metricKey = new MetricFactKey(fact.getJournalId(), fact.getYear(), fact.getMetricType());
            WosMetricFact metricFact = metricByKey.get(metricKey);
            Double metricValue = metricFact == null ? null : metricFact.getValue();
            if (metricValue == null) {
                uncomputable.add(fact);
                continue;
            }
            ranked.add(new RankCandidate(fact, metricValue));
        }

        ranked.sort(Comparator
                .comparing(RankCandidate::metricValue, Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.fact().getJournalId(), Comparator.nullsFirst(String::compareTo)));

        Map<String, Integer> rankByFactId = competitionRanks(ranked);
        Map<String, String> computedQuarterByFactId = computedQuarterByFactId(ranked);
        Map<String, String> effectiveQuarterByFactId = new HashMap<>();
        for (RankCandidate candidate : ranked) {
            WosCategoryFact fact = candidate.fact();
            String sourceQuarter = normalizeQuarter(fact.getQuarter());
            String computedQuarter = computedQuarterByFactId.get(fact.getId());
            effectiveQuarterByFactId.put(fact.getId(), sourceQuarter == null ? computedQuarter : sourceQuarter);
        }
        Map<String, Integer> quartileRankByFactId = quartileRankByFactId(ranked, effectiveQuarterByFactId);

        for (RankCandidate candidate : ranked) {
            WosCategoryFact fact = candidate.fact();
            boolean changed = false;

            if (fact.getRank() == null) {
                Integer computedRank = rankByFactId.get(fact.getId());
                if (computedRank != null) {
                    fact.setRank(computedRank);
                    changed = true;
                }
            }
            if (isBlank(fact.getQuarter())) {
                String computedQuarter = computedQuarterByFactId.get(fact.getId());
                if (computedQuarter != null) {
                    fact.setQuarter(computedQuarter);
                    changed = true;
                }
            }
            if (fact.getQuartileRank() == null) {
                Integer computedQuartileRank = quartileRankByFactId.get(fact.getId());
                if (computedQuartileRank != null) {
                    fact.setQuartileRank(computedQuartileRank);
                    changed = true;
                }
            }

            if (changed) {
                fact.setCreatedAt(Instant.now());
                pendingUpdates.add(fact);
                result.markUpdated();
            }
        }

        for (WosCategoryFact fact : uncomputable) {
            if (requiresCategoryRankingEnrichment(fact)) {
                result.markSkipped("missing-metric-value factId=" + fact.getId());
            }
        }
    }

    private Map<String, Integer> competitionRanks(List<RankCandidate> ranked) {
        Map<String, Integer> byFactId = new HashMap<>();
        Double previousValue = null;
        int currentRank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            RankCandidate candidate = ranked.get(i);
            if (previousValue == null || Double.compare(previousValue, candidate.metricValue()) != 0) {
                currentRank = i + 1;
            }
            byFactId.put(candidate.fact().getId(), currentRank);
            previousValue = candidate.metricValue();
        }
        return byFactId;
    }

    private Map<String, String> computedQuarterByFactId(List<RankCandidate> ranked) {
        Map<String, String> byFactId = new HashMap<>();
        int n = ranked.size();
        if (n == 0) {
            return byFactId;
        }
        int q1End = (int) Math.ceil(n / 4.0d);
        int q2End = (int) Math.ceil(n / 2.0d);
        int q3End = (int) Math.ceil((3.0d * n) / 4.0d);
        for (int i = 0; i < ranked.size(); i++) {
            int position = i + 1;
            String quarter;
            if (position <= q1End) {
                quarter = "Q1";
            } else if (position <= q2End) {
                quarter = "Q2";
            } else if (position <= q3End) {
                quarter = "Q3";
            } else {
                quarter = "Q4";
            }
            byFactId.put(ranked.get(i).fact().getId(), quarter);
        }
        return byFactId;
    }

    private Map<String, Integer> quartileRankByFactId(
            List<RankCandidate> ranked,
            Map<String, String> effectiveQuarterByFactId
    ) {
        Map<String, Integer> byFactId = new HashMap<>();
        Map<String, List<RankCandidate>> byQuarter = new LinkedHashMap<>();
        for (RankCandidate candidate : ranked) {
            String quarter = normalizeQuarter(effectiveQuarterByFactId.get(candidate.fact().getId()));
            if (quarter == null) {
                continue;
            }
            byQuarter.computeIfAbsent(quarter, ignored -> new ArrayList<>()).add(candidate);
        }
        for (List<RankCandidate> quarterCandidates : byQuarter.values()) {
            Map<String, Integer> quarterRanks = competitionRanks(quarterCandidates);
            byFactId.putAll(quarterRanks);
        }
        return byFactId;
    }

    private boolean requiresCategoryRankingEnrichment(WosCategoryFact fact) {
        return fact != null && (fact.getRank() == null || isBlank(fact.getQuarter()) || fact.getQuartileRank() == null);
    }

    private String normalizeQuarter(String quarter) {
        if (quarter == null) {
            return null;
        }
        String normalized = quarter.trim().toUpperCase();
        return normalized.isBlank() ? null : normalized;
    }

    private record WinnerDecision<T>(boolean incomingWins, String reason) {
        static <T> WinnerDecision<T> incoming(String reason) {
            return new WinnerDecision<>(true, reason);
        }

        static <T> WinnerDecision<T> existing(String reason) {
            return new WinnerDecision<>(false, reason);
        }
    }

    private record ResolvedRecord(String journalId, WosParsedRecord record) {
    }

    public record FactBuildRunResult(
            ImportProcessingResult result,
            int startBatch,
            int endBatch,
            int batchesProcessed,
            boolean resumedFromCheckpoint,
            int checkpointLastCompletedBatch
    ) {
    }

    private record MetricFactKey(
            String journalId,
            Integer year,
            MetricType metricType
    ) {
    }

    private record MetricPreloadGroup(
            Integer year,
            MetricType metricType
    ) {
    }

    private record CategoryFactKey(
            String journalId,
            Integer year,
            MetricType metricType,
            String categoryNameCanonical,
            EditionNormalized editionNormalized
    ) {
    }

    private record CategoryPreloadGroup(
            Integer year,
            MetricType metricType,
            String categoryNameCanonical,
            EditionNormalized editionNormalized
    ) {
    }

    private record CategoryEnrichmentGroupKey(
            Integer year,
            MetricType metricType,
            String categoryNameCanonical,
            EditionNormalized editionNormalized
    ) {
    }

    private record RankCandidate(
            WosCategoryFact fact,
            Double metricValue
    ) {
    }
}
