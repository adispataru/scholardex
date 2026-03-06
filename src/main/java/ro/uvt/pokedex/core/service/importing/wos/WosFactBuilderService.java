package ro.uvt.pokedex.core.service.importing.wos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
import java.util.Comparator;
import java.util.Optional;

@Service
public class WosFactBuilderService {
    private static final Logger log = LoggerFactory.getLogger(WosFactBuilderService.class);

    private final WosImportEventParserOrchestrator parserOrchestrator;
    private final WosIdentityResolutionService identityResolutionService;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final WosFactConflictRepository factConflictRepository;

    public WosFactBuilderService(
            WosImportEventParserOrchestrator parserOrchestrator,
            WosIdentityResolutionService identityResolutionService,
            WosMetricFactRepository metricFactRepository,
            WosCategoryFactRepository categoryFactRepository,
            WosFactConflictRepository factConflictRepository
    ) {
        this.parserOrchestrator = parserOrchestrator;
        this.identityResolutionService = identityResolutionService;
        this.metricFactRepository = metricFactRepository;
        this.categoryFactRepository = categoryFactRepository;
        this.factConflictRepository = factConflictRepository;
    }

    public ImportProcessingResult buildFactsFromImportEvents() {
        WosParserRunResult parserRun = parserOrchestrator.parseAllEvents();
        ImportProcessingResult result = new ImportProcessingResult(20);

        for (WosParsedRecord record : parserRun.records()) {
            result.markProcessed();
            if (!WosCanonicalContractSupport.isSourceAllowedForMetric(record.metricType(), record.sourceType())) {
                result.markSkipped("source-not-allowed metric=" + record.metricType() + ", source=" + record.sourceType()
                        + ", sourceRef=" + record.sourceFile() + "#" + record.sourceRowItem());
                continue;
            }
            String journalId = resolveJournalId(record, result);
            if (journalId == null || journalId.isBlank()) {
                continue;
            }
            upsertMetricFact(journalId, record, result);
            upsertCategoryFact(journalId, record, result);
        }
        log.info("WoS fact-builder summary: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                result.getSkippedCount(), result.getErrorCount(), result.getErrorsSample());
        return result;
    }

    private String resolveJournalId(WosParsedRecord record, ImportProcessingResult result) {
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
                return null;
            }
            return identity.journalId();
        } catch (Exception e) {
            result.markError("identity-error source=" + record.sourceFile() + "#" + record.sourceRowItem() + " " + e.getMessage());
            return null;
        }
    }

    private void upsertMetricFact(String journalId, WosParsedRecord record, ImportProcessingResult result) {
        if (record.metricType() == null || record.year() == null || record.editionNormalized() == null) {
            result.markSkipped("metric-key-incomplete source=" + record.sourceFile() + "#" + record.sourceRowItem());
            return;
        }
        Optional<WosMetricFact> existingOpt = metricFactRepository.findByJournalIdAndYearAndMetricTypeAndEditionNormalized(
                journalId, record.year(), record.metricType(), record.editionNormalized());
        if (existingOpt.isEmpty()) {
            metricFactRepository.save(toMetricFact(journalId, record));
            result.markImported();
            return;
        }
        WosMetricFact existing = existingOpt.get();
        if (isSameMetric(existing, record)) {
            result.markSkipped("metric-unchanged key=" + metricKey(journalId, record));
            return;
        }
        WinnerDecision<WosMetricFact> decision = decideMetricWinner(existing, record);
        if (decision.incomingWins()) {
            WosMetricFact loserSnapshot = copyMetric(existing);
            WosMetricFact updated = applyMetricRecord(existing, record);
            metricFactRepository.save(updated);
            logMetricConflictIncomingWinner(decision.reason(), metricKey(journalId, record), record, loserSnapshot);
            result.markUpdated();
        } else {
            logMetricConflictExistingWinner(decision.reason(), metricKey(journalId, record), existing, record);
            result.markSkipped("metric-conflict-loser key=" + metricKey(journalId, record));
        }
    }

    private void upsertCategoryFact(String journalId, WosParsedRecord record, ImportProcessingResult result) {
        if (record.metricType() == null || record.year() == null || record.editionNormalized() == null) {
            return;
        }
        if (record.categoryNameCanonical() == null || record.categoryNameCanonical().isBlank()) {
            return;
        }
        Optional<WosCategoryFact> existingOpt =
                categoryFactRepository.findByJournalIdAndYearAndCategoryNameCanonicalAndEditionNormalizedAndMetricType(
                        journalId, record.year(), record.categoryNameCanonical(), record.editionNormalized(), record.metricType());
        if (existingOpt.isEmpty()) {
            categoryFactRepository.save(toCategoryFact(journalId, record));
            result.markImported();
            return;
        }
        WosCategoryFact existing = existingOpt.get();
        if (isSameCategory(existing, record)) {
            result.markSkipped("category-unchanged key=" + categoryKey(journalId, record));
            return;
        }
        WinnerDecision<WosCategoryFact> decision = decideCategoryWinner(existing, record);
        if (decision.incomingWins()) {
            WosCategoryFact loserSnapshot = copyCategory(existing);
            WosCategoryFact updated = applyCategoryRecord(existing, record);
            categoryFactRepository.save(updated);
            logCategoryConflictIncomingWinner(decision.reason(), categoryKey(journalId, record), record, loserSnapshot);
            result.markUpdated();
        } else {
            logCategoryConflictExistingWinner(decision.reason(), categoryKey(journalId, record), existing, record);
            result.markSkipped("category-conflict-loser key=" + categoryKey(journalId, record));
        }
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

    private String metricKey(String journalId, WosParsedRecord record) {
        return "metric|" + journalId + "|" + record.year() + "|" + record.metricType() + "|" + record.editionNormalized();
    }

    private String categoryKey(String journalId, WosParsedRecord record) {
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

    private record WinnerDecision<T>(boolean incomingWins, String reason) {
        static <T> WinnerDecision<T> incoming(String reason) {
            return new WinnerDecision<>(true, reason);
        }

        static <T> WinnerDecision<T> existing(String reason) {
            return new WinnerDecision<>(false, reason);
        }
    }
}
