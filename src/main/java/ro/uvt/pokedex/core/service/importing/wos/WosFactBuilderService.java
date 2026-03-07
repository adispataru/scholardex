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
import ro.uvt.pokedex.core.model.reporting.wos.WosFactConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactBuildCheckpoint;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
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
    private static final String IDENTITY_NULL_MARKER = "__NULL__";

    private final WosImportEventParserOrchestrator parserOrchestrator;
    private final WosIdentityResolutionService identityResolutionService;
    private final WosMetricFactRepository metricFactRepository;
    private final WosFactConflictRepository factConflictRepository;
    private final MongoTemplate mongoTemplate;
    private final WosFactBuildCheckpointService checkpointService;
    private final Counter ifSourcePolicySkipCounter;

    public WosFactBuilderService(
            WosImportEventParserOrchestrator parserOrchestrator,
            WosIdentityResolutionService identityResolutionService,
            WosMetricFactRepository metricFactRepository,
            WosFactConflictRepository factConflictRepository,
            MongoTemplate mongoTemplate,
            WosFactBuildCheckpointService checkpointService,
            MeterRegistry meterRegistry
    ) {
        this.parserOrchestrator = parserOrchestrator;
        this.identityResolutionService = identityResolutionService;
        this.metricFactRepository = metricFactRepository;
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
                log.info("WoS fact-builder progress: processed={} imported={} updated={} skipped={} errors={}",
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
        long upsertStartedAtNanos = System.nanoTime();
        Map<MetricFactKey, WosMetricFact> pendingMetricSaves = new LinkedHashMap<>();
        List<WosFactConflict> pendingConflicts = new ArrayList<>();

        for (ResolvedRecord resolvedRecord : resolved) {
            upsertMetricFact(resolvedRecord, result, existingMetrics, pendingMetricSaves, pendingConflicts);
        }

        long saveStartedAtNanos = System.nanoTime();
        if (!pendingMetricSaves.isEmpty()) {
            metricFactRepository.saveAll(pendingMetricSaves.values());
        }
        if (!pendingConflicts.isEmpty()) {
            factConflictRepository.saveAll(pendingConflicts);
        }

        if (ifPolicySkipped > 0) {
            log.warn("WoS IF source-policy skips in chunk {} (batch {} of {}): skipped={}, samples={}",
                    chunkNo, batchIndex, totalBatches, ifPolicySkipped, ifPolicySkipSamples);
        }

        long finishedAtNanos = System.nanoTime();
        log.info("WoS fact-builder chunk {} complete [batch={} / totalBatches={}]: resolved={} metricWrites={} conflictWrites={} timingsMs[identity+filter={}, preload={}, upsert={}, save={}, total={}]",
                chunkNo,
                batchIndex,
                totalBatches,
                resolved.size(),
                pendingMetricSaves.size(),
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
            WosParsedRecord record = rr.record();
            MetricFactKey key = metricKey(rr.journalId(), record);
            if (key != null) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<MetricPreloadGroup, Set<String>> groupToJournalIds = new LinkedHashMap<>();
        for (MetricFactKey key : keys) {
            MetricPreloadGroup group = new MetricPreloadGroup(
                    key.year(),
                    key.metricType(),
                    key.categoryNameCanonical(),
                    key.editionNormalized()
            );
            groupToJournalIds.computeIfAbsent(group, ignored -> new LinkedHashSet<>()).add(key.journalId());
        }

        List<WosMetricFact> existing = new ArrayList<>();
        for (Map.Entry<MetricPreloadGroup, Set<String>> entry : groupToJournalIds.entrySet()) {
            MetricPreloadGroup group = entry.getKey();
            Query query = new Query(new Criteria().andOperator(
                    Criteria.where("year").is(group.year()),
                    Criteria.where("metricType").is(group.metricType()),
                    Criteria.where("categoryNameCanonical").is(group.categoryNameCanonical()),
                    Criteria.where("editionNormalized").is(group.editionNormalized()),
                    Criteria.where("journalId").in(entry.getValue())
            ));
            existing.addAll(mongoTemplate.find(query, WosMetricFact.class));
        }

        Map<MetricFactKey, WosMetricFact> out = new LinkedHashMap<>();
        for (WosMetricFact fact : existing) {
            MetricFactKey key = new MetricFactKey(
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
            result.markSkipped("metric-key-incomplete source=" + record.sourceFile() + "#" + record.sourceRowItem());
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

        if (isSameMetric(existing, record)) {
            result.markSkipped("metric-unchanged key=" + metricKeyString(resolved.journalId(), record));
            return;
        }

        WinnerDecision<WosMetricFact> decision = decideMetricWinner(existing, record);
        if (decision.incomingWins()) {
            WosMetricFact loserSnapshot = copyMetric(existing);
            WosMetricFact updated = applyMetricRecord(existing, record);
            pendingMetricSaves.put(key, updated);
            pendingConflicts.add(buildMetricConflictIncomingWinner(
                    decision.reason(),
                    metricKeyString(resolved.journalId(), record),
                    record,
                    loserSnapshot
            ));
            result.markUpdated();
        } else {
            pendingConflicts.add(buildMetricConflictExistingWinner(
                    decision.reason(),
                    metricKeyString(resolved.journalId(), record),
                    existing,
                    record
            ));
            result.markSkipped("metric-conflict-loser key=" + metricKeyString(resolved.journalId(), record));
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
        if (record.metricType() == null || record.year() == null || record.editionNormalized() == null) {
            return null;
        }
        if (record.categoryNameCanonical() == null || record.categoryNameCanonical().isBlank()) {
            return null;
        }
        return new MetricFactKey(
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

    private boolean isSameMetric(WosMetricFact fact, WosParsedRecord record) {
        return safeEq(fact.getValue(), record.value())
                && safeEq(fact.getQuarter(), record.quarter())
                && safeEq(fact.getRank(), record.rank())
                && safeEq(fact.getSourceType(), record.sourceType())
                && safeEq(fact.getSourceVersion(), record.sourceVersion())
                && safeEq(fact.getSourceRowItem(), record.sourceRowItem())
                && safeEq(fact.getSourceEventId(), record.sourceEventId());
    }

    private boolean safeEq(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private WosMetricFact toMetricFact(String journalId, WosParsedRecord record) {
        WosMetricFact fact = new WosMetricFact();
        fact.setJournalId(journalId);
        fact.setYear(record.year());
        fact.setMetricType(record.metricType());
        fact.setCategoryNameCanonical(record.categoryNameCanonical());
        fact.setValue(record.value());
        fact.setQuarter(record.quarter());
        fact.setRank(record.rank());
        fact.setEditionRaw(record.editionRaw());
        fact.setEditionNormalized(record.editionNormalized());
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
        fact.setQuarter(record.quarter());
        fact.setRank(record.rank());
        fact.setEditionRaw(record.editionRaw());
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
        conflict.setFactType("METRIC");
        conflict.setConflictReason(reason);
        conflict.setFactKey(factKey);
        conflict.setWinnerSourceEventId(winner.getSourceEventId());
        conflict.setWinnerSourceFile(winner.getSourceFile());
        conflict.setWinnerSourceVersion(winner.getSourceVersion());
        conflict.setWinnerSourceRowItem(winner.getSourceRowItem());
        conflict.setWinnerSourceType(winner.getSourceType());
        conflict.setWinnerValueSnapshot(
                (winner.getCategoryNameCanonical() == null ? "" : winner.getCategoryNameCanonical())
                        + "|" + winner.getValue()
                        + "|" + (winner.getQuarter() == null ? "" : winner.getQuarter())
                        + "|" + winner.getRank()
        );
        conflict.setLoserSourceEventId(loser.sourceEventId());
        conflict.setLoserSourceFile(loser.sourceFile());
        conflict.setLoserSourceVersion(loser.sourceVersion());
        conflict.setLoserSourceRowItem(loser.sourceRowItem());
        conflict.setLoserSourceType(loser.sourceType());
        conflict.setLoserValueSnapshot(
                (loser.categoryNameCanonical() == null ? "" : loser.categoryNameCanonical())
                        + "|" + loser.value()
                        + "|" + (loser.quarter() == null ? "" : loser.quarter())
                        + "|" + loser.rank()
        );
        conflict.setDetectedAt(Instant.now());
        return conflict;
    }

    private WosFactConflict buildMetricConflictIncomingWinner(String reason, String factKey, WosParsedRecord winner, WosMetricFact loser) {
        WosFactConflict conflict = new WosFactConflict();
        conflict.setFactType("METRIC");
        conflict.setConflictReason(reason);
        conflict.setFactKey(factKey);
        conflict.setWinnerSourceEventId(winner.sourceEventId());
        conflict.setWinnerSourceFile(winner.sourceFile());
        conflict.setWinnerSourceVersion(winner.sourceVersion());
        conflict.setWinnerSourceRowItem(winner.sourceRowItem());
        conflict.setWinnerSourceType(winner.sourceType());
        conflict.setWinnerValueSnapshot(
                (winner.categoryNameCanonical() == null ? "" : winner.categoryNameCanonical())
                        + "|" + winner.value()
                        + "|" + (winner.quarter() == null ? "" : winner.quarter())
                        + "|" + winner.rank()
        );
        conflict.setLoserSourceEventId(loser.getSourceEventId());
        conflict.setLoserSourceFile(loser.getSourceFile());
        conflict.setLoserSourceVersion(loser.getSourceVersion());
        conflict.setLoserSourceRowItem(loser.getSourceRowItem());
        conflict.setLoserSourceType(loser.getSourceType());
        conflict.setLoserValueSnapshot(
                (loser.getCategoryNameCanonical() == null ? "" : loser.getCategoryNameCanonical())
                        + "|" + loser.getValue()
                        + "|" + (loser.getQuarter() == null ? "" : loser.getQuarter())
                        + "|" + loser.getRank()
        );
        conflict.setDetectedAt(Instant.now());
        return conflict;
    }

    private String metricKeyString(String journalId, WosParsedRecord record) {
        return "metric|"
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
        copy.setCategoryNameCanonical(source.getCategoryNameCanonical());
        copy.setValue(source.getValue());
        copy.setQuarter(source.getQuarter());
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
            MetricType metricType,
            String categoryNameCanonical,
            EditionNormalized editionNormalized
    ) {
    }

    private record MetricPreloadGroup(
            Integer year,
            MetricType metricType,
            String categoryNameCanonical,
            EditionNormalized editionNormalized
    ) {
    }

}
