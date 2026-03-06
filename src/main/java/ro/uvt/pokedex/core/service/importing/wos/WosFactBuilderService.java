package ro.uvt.pokedex.core.service.importing.wos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
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
    private final WosCategoryFactRepository categoryFactRepository;
    private final WosFactConflictRepository factConflictRepository;
    private final MongoTemplate mongoTemplate;

    public WosFactBuilderService(
            WosImportEventParserOrchestrator parserOrchestrator,
            WosIdentityResolutionService identityResolutionService,
            WosMetricFactRepository metricFactRepository,
            WosCategoryFactRepository categoryFactRepository,
            WosFactConflictRepository factConflictRepository,
            MongoTemplate mongoTemplate
    ) {
        this.parserOrchestrator = parserOrchestrator;
        this.identityResolutionService = identityResolutionService;
        this.metricFactRepository = metricFactRepository;
        this.categoryFactRepository = categoryFactRepository;
        this.factConflictRepository = factConflictRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public ImportProcessingResult buildFactsFromImportEvents() {
        WosParserRunResult parserRun = parserOrchestrator.parseAllEvents();
        ImportProcessingResult result = new ImportProcessingResult(20);
        Map<String, String> identityCache = new HashMap<>();

        List<WosParsedRecord> records = parserRun.records();
        int total = records.size();
        int chunkNo = 0;
        for (int from = 0; from < total; from += FACT_BUILD_CHUNK_SIZE) {
            int to = Math.min(total, from + FACT_BUILD_CHUNK_SIZE);
            chunkNo++;
            processChunk(records.subList(from, to), result, identityCache, chunkNo, from + 1, to, total);
        }

        log.info("WoS fact-builder summary: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                result.getSkippedCount(), result.getErrorCount(), result.getErrorsSample());
        return result;
    }

    private void processChunk(
            List<WosParsedRecord> chunk,
            ImportProcessingResult result,
            Map<String, String> identityCache,
            int chunkNo,
            int fromInclusive,
            int toInclusive,
            int total
    ) {
        List<ResolvedRecord> resolved = new ArrayList<>(chunk.size());
        for (WosParsedRecord record : chunk) {
            result.markProcessed();
            if (result.getProcessedCount() % FACT_BUILD_HEARTBEAT_INTERVAL == 0) {
                log.info("WoS fact-builder progress: processed={} imported={} updated={} skipped={} errors={}",
                        result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                        result.getSkippedCount(), result.getErrorCount());
            }
            if (!WosCanonicalContractSupport.isSourceAllowedForMetric(record.metricType(), record.sourceType())) {
                result.markSkipped("source-not-allowed metric=" + record.metricType() + ", source=" + record.sourceType()
                        + ", sourceRef=" + record.sourceFile() + "#" + record.sourceRowItem());
                continue;
            }
            String journalId = resolveJournalId(record, result, identityCache);
            if (journalId == null || journalId.isBlank()) {
                continue;
            }
            resolved.add(new ResolvedRecord(journalId, record));
        }

        Map<MetricFactKey, WosMetricFact> existingMetrics = preloadMetricFacts(resolved);
        Map<CategoryFactKey, WosCategoryFact> existingCategories = preloadCategoryFacts(resolved);
        Map<MetricFactKey, WosMetricFact> pendingMetricSaves = new LinkedHashMap<>();
        Map<CategoryFactKey, WosCategoryFact> pendingCategorySaves = new LinkedHashMap<>();

        for (ResolvedRecord resolvedRecord : resolved) {
            upsertMetricFact(resolvedRecord, result, existingMetrics, pendingMetricSaves);
            upsertCategoryFact(resolvedRecord, result, existingCategories, pendingCategorySaves);
        }

        if (!pendingMetricSaves.isEmpty()) {
            metricFactRepository.saveAll(pendingMetricSaves.values());
        }
        if (!pendingCategorySaves.isEmpty()) {
            categoryFactRepository.saveAll(pendingCategorySaves.values());
        }

        log.info("WoS fact-builder chunk {} complete [{}-{} / {}]: resolved={} metricWrites={} categoryWrites={}",
                chunkNo, fromInclusive, toInclusive, total, resolved.size(),
                pendingMetricSaves.size(), pendingCategorySaves.size());
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

        List<Criteria> ors = new ArrayList<>(keys.size());
        for (MetricFactKey key : keys) {
            ors.add(new Criteria().andOperator(
                    Criteria.where("journalId").is(key.journalId()),
                    Criteria.where("year").is(key.year()),
                    Criteria.where("metricType").is(key.metricType()),
                    Criteria.where("editionNormalized").is(key.editionNormalized())
            ));
        }

        Query query = new Query();
        if (ors.size() == 1) {
            query.addCriteria(ors.getFirst());
        } else {
            query.addCriteria(new Criteria().orOperator(ors.toArray(new Criteria[0])));
        }

        List<WosMetricFact> existing = mongoTemplate.find(query, WosMetricFact.class);
        Map<MetricFactKey, WosMetricFact> out = new LinkedHashMap<>();
        for (WosMetricFact fact : existing) {
            MetricFactKey key = new MetricFactKey(fact.getJournalId(), fact.getYear(), fact.getMetricType(), fact.getEditionNormalized());
            out.put(key, fact);
        }
        return out;
    }

    private Map<CategoryFactKey, WosCategoryFact> preloadCategoryFacts(List<ResolvedRecord> resolved) {
        Set<CategoryFactKey> keys = new LinkedHashSet<>();
        for (ResolvedRecord rr : resolved) {
            WosParsedRecord record = rr.record();
            CategoryFactKey key = categoryKey(rr.journalId(), record);
            if (key != null) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return new LinkedHashMap<>();
        }

        List<Criteria> ors = new ArrayList<>(keys.size());
        for (CategoryFactKey key : keys) {
            ors.add(new Criteria().andOperator(
                    Criteria.where("journalId").is(key.journalId()),
                    Criteria.where("year").is(key.year()),
                    Criteria.where("categoryNameCanonical").is(key.categoryNameCanonical()),
                    Criteria.where("editionNormalized").is(key.editionNormalized()),
                    Criteria.where("metricType").is(key.metricType())
            ));
        }

        Query query = new Query();
        if (ors.size() == 1) {
            query.addCriteria(ors.getFirst());
        } else {
            query.addCriteria(new Criteria().orOperator(ors.toArray(new Criteria[0])));
        }

        List<WosCategoryFact> existing = mongoTemplate.find(query, WosCategoryFact.class);
        Map<CategoryFactKey, WosCategoryFact> out = new LinkedHashMap<>();
        for (WosCategoryFact fact : existing) {
            CategoryFactKey key = new CategoryFactKey(
                    fact.getJournalId(), fact.getYear(), fact.getCategoryNameCanonical(), fact.getEditionNormalized(), fact.getMetricType()
            );
            out.put(key, fact);
        }
        return out;
    }

    private String resolveJournalId(WosParsedRecord record, ImportProcessingResult result, Map<String, String> identityCache) {
        String cacheKey = identityCacheKey(record);
        if (identityCache.containsKey(cacheKey)) {
            String cached = identityCache.get(cacheKey);
            return IDENTITY_NULL_MARKER.equals(cached) ? null : cached;
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
            Map<MetricFactKey, WosMetricFact> pendingMetricSaves
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
            logMetricConflictIncomingWinner(decision.reason(), metricKeyString(resolved.journalId(), record), record, loserSnapshot);
            result.markUpdated();
        } else {
            logMetricConflictExistingWinner(decision.reason(), metricKeyString(resolved.journalId(), record), existing, record);
            result.markSkipped("metric-conflict-loser key=" + metricKeyString(resolved.journalId(), record));
        }
    }

    private void upsertCategoryFact(
            ResolvedRecord resolved,
            ImportProcessingResult result,
            Map<CategoryFactKey, WosCategoryFact> existingCategories,
            Map<CategoryFactKey, WosCategoryFact> pendingCategorySaves
    ) {
        WosParsedRecord record = resolved.record();
        CategoryFactKey key = categoryKey(resolved.journalId(), record);
        if (key == null) {
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

        if (isSameCategory(existing, record)) {
            result.markSkipped("category-unchanged key=" + categoryKeyString(resolved.journalId(), record));
            return;
        }

        WinnerDecision<WosCategoryFact> decision = decideCategoryWinner(existing, record);
        if (decision.incomingWins()) {
            WosCategoryFact loserSnapshot = copyCategory(existing);
            WosCategoryFact updated = applyCategoryRecord(existing, record);
            pendingCategorySaves.put(key, updated);
            logCategoryConflictIncomingWinner(decision.reason(), categoryKeyString(resolved.journalId(), record), record, loserSnapshot);
            result.markUpdated();
        } else {
            logCategoryConflictExistingWinner(decision.reason(), categoryKeyString(resolved.journalId(), record), existing, record);
            result.markSkipped("category-conflict-loser key=" + categoryKeyString(resolved.journalId(), record));
        }
    }

    private MetricFactKey metricKey(String journalId, WosParsedRecord record) {
        if (record.metricType() == null || record.year() == null || record.editionNormalized() == null) {
            return null;
        }
        return new MetricFactKey(journalId, record.year(), record.metricType(), record.editionNormalized());
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
                record.categoryNameCanonical(),
                record.editionNormalized(),
                record.metricType()
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

    private boolean isSameMetric(WosMetricFact fact, WosParsedRecord record) {
        return safeEq(fact.getValue(), record.value())
                && safeEq(fact.getSourceType(), record.sourceType())
                && safeEq(fact.getSourceVersion(), record.sourceVersion())
                && safeEq(fact.getSourceRowItem(), record.sourceRowItem())
                && safeEq(fact.getSourceEventId(), record.sourceEventId());
    }

    private boolean isSameCategory(WosCategoryFact fact, WosParsedRecord record) {
        return safeEq(fact.getQuarter(), record.quarter())
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
        fact.setValue(record.value());
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

    private WosCategoryFact toCategoryFact(String journalId, WosParsedRecord record) {
        WosCategoryFact fact = new WosCategoryFact();
        fact.setJournalId(journalId);
        fact.setYear(record.year());
        fact.setCategoryNameCanonical(record.categoryNameCanonical());
        fact.setEditionRaw(record.editionRaw());
        fact.setEditionNormalized(record.editionNormalized());
        fact.setMetricType(record.metricType());
        fact.setQuarter(record.quarter());
        fact.setRank(record.rank());
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
        fact.setEditionRaw(record.editionRaw());
        fact.setSourceType(record.sourceType());
        fact.setSourceEventId(record.sourceEventId());
        fact.setSourceFile(record.sourceFile());
        fact.setSourceVersion(record.sourceVersion());
        fact.setSourceRowItem(record.sourceRowItem());
        fact.setCreatedAt(Instant.now());
        return fact;
    }

    private WosCategoryFact applyCategoryRecord(WosCategoryFact fact, WosParsedRecord record) {
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

    private void logMetricConflictExistingWinner(String reason, String factKey, WosMetricFact winner, WosParsedRecord loser) {
        WosFactConflict conflict = new WosFactConflict();
        conflict.setFactType("METRIC");
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
        factConflictRepository.save(conflict);
    }

    private void logMetricConflictIncomingWinner(String reason, String factKey, WosParsedRecord winner, WosMetricFact loser) {
        WosFactConflict conflict = new WosFactConflict();
        conflict.setFactType("METRIC");
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
        factConflictRepository.save(conflict);
    }

    private void logCategoryConflictExistingWinner(String reason, String factKey, WosCategoryFact winner, WosParsedRecord loser) {
        WosFactConflict conflict = new WosFactConflict();
        conflict.setFactType("CATEGORY");
        conflict.setConflictReason(reason);
        conflict.setFactKey(factKey);
        conflict.setWinnerSourceEventId(winner.getSourceEventId());
        conflict.setWinnerSourceFile(winner.getSourceFile());
        conflict.setWinnerSourceVersion(winner.getSourceVersion());
        conflict.setWinnerSourceRowItem(winner.getSourceRowItem());
        conflict.setWinnerSourceType(winner.getSourceType());
        conflict.setWinnerValueSnapshot((winner.getQuarter() == null ? "" : winner.getQuarter()) + "|" + winner.getRank());
        conflict.setLoserSourceEventId(loser.sourceEventId());
        conflict.setLoserSourceFile(loser.sourceFile());
        conflict.setLoserSourceVersion(loser.sourceVersion());
        conflict.setLoserSourceRowItem(loser.sourceRowItem());
        conflict.setLoserSourceType(loser.sourceType());
        conflict.setLoserValueSnapshot((loser.quarter() == null ? "" : loser.quarter()) + "|" + loser.rank());
        conflict.setDetectedAt(Instant.now());
        factConflictRepository.save(conflict);
    }

    private void logCategoryConflictIncomingWinner(String reason, String factKey, WosParsedRecord winner, WosCategoryFact loser) {
        WosFactConflict conflict = new WosFactConflict();
        conflict.setFactType("CATEGORY");
        conflict.setConflictReason(reason);
        conflict.setFactKey(factKey);
        conflict.setWinnerSourceEventId(winner.sourceEventId());
        conflict.setWinnerSourceFile(winner.sourceFile());
        conflict.setWinnerSourceVersion(winner.sourceVersion());
        conflict.setWinnerSourceRowItem(winner.sourceRowItem());
        conflict.setWinnerSourceType(winner.sourceType());
        conflict.setWinnerValueSnapshot((winner.quarter() == null ? "" : winner.quarter()) + "|" + winner.rank());
        conflict.setLoserSourceEventId(loser.getSourceEventId());
        conflict.setLoserSourceFile(loser.getSourceFile());
        conflict.setLoserSourceVersion(loser.getSourceVersion());
        conflict.setLoserSourceRowItem(loser.getSourceRowItem());
        conflict.setLoserSourceType(loser.getSourceType());
        conflict.setLoserValueSnapshot((loser.getQuarter() == null ? "" : loser.getQuarter()) + "|" + loser.getRank());
        conflict.setDetectedAt(Instant.now());
        factConflictRepository.save(conflict);
    }

    private String metricKeyString(String journalId, WosParsedRecord record) {
        return "metric|" + journalId + "|" + record.year() + "|" + record.metricType() + "|" + record.editionNormalized();
    }

    private String categoryKeyString(String journalId, WosParsedRecord record) {
        return "category|" + journalId + "|" + record.year() + "|" + record.categoryNameCanonical() + "|" + record.metricType() + "|" + record.editionNormalized();
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
        copy.setQuarter(source.getQuarter());
        copy.setRank(source.getRank());
        return copy;
    }

    private String identityCacheKey(WosParsedRecord record) {
        return normalizeIdentityPart(record.issn())
                + "|" + normalizeIdentityPart(record.eIssn())
                + "|" + normalizeIdentityPart(record.title());
    }

    private String normalizeIdentityPart(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
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

    private record MetricFactKey(String journalId, Integer year, MetricType metricType, EditionNormalized editionNormalized) {
    }

    private record CategoryFactKey(
            String journalId,
            Integer year,
            String categoryNameCanonical,
            EditionNormalized editionNormalized,
            MetricType metricType
    ) {
    }
}
