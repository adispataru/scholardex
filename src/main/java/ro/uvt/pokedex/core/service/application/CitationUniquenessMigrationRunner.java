package ro.uvt.pokedex.core.service.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.service.application.CitationUniquenessMigrationService.DedupeResult;
import ro.uvt.pokedex.core.service.application.CitationUniquenessMigrationService.DuplicatePair;
import ro.uvt.pokedex.core.service.application.CitationUniquenessMigrationService.DuplicateScanResult;
import ro.uvt.pokedex.core.service.application.CitationUniquenessMigrationService.VerificationResult;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class CitationUniquenessMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CitationUniquenessMigrationRunner.class);

    private final CitationUniquenessMigrationService migrationService;
    private final String mode;
    private final boolean failOnDuplicates;
    private final int logSampleSize;

    public CitationUniquenessMigrationRunner(CitationUniquenessMigrationService migrationService,
                                             @Value("${h06.citation.uniqueness.mode:off}") String mode,
                                             @Value("${h06.citation.uniqueness.fail-on-duplicates:false}") boolean failOnDuplicates,
                                             @Value("${h06.citation.uniqueness.log-sample-size:20}") int logSampleSize) {
        this.migrationService = migrationService;
        this.mode = mode;
        this.failOnDuplicates = failOnDuplicates;
        this.logSampleSize = logSampleSize;
    }

    @Override
    public void run(String... args) {
        Mode effectiveMode = Mode.fromRaw(mode);
        if (effectiveMode == Mode.OFF) {
            return;
        }

        DuplicateScanResult scanResult = migrationService.scanDuplicates();
        logDuplicateSummary(scanResult, effectiveMode);

        if (effectiveMode == Mode.REPORT) {
            if (failOnDuplicates && scanResult.duplicateGroupCount() > 0) {
                throw new IllegalStateException("H06 citation uniqueness report found duplicate pairs.");
            }
            return;
        }

        DedupeResult dedupeResult = migrationService.applyDedupeKeepingLowestId(scanResult);
        migrationService.ensureUniqueIndex();
        VerificationResult verification = migrationService.verifyPostConditions();

        log.info(
                "H06 citation uniqueness apply completed: affectedPairs={} deletedRows={} duplicatesRemoved={} uniqueIndexPresent={}",
                dedupeResult.affectedPairs(),
                dedupeResult.deletedRows(),
                verification.duplicatesRemoved(),
                verification.uniqueIndexPresent()
        );

        if (!verification.duplicatesRemoved() || !verification.uniqueIndexPresent()) {
            throw new IllegalStateException("H06 citation uniqueness apply verification failed.");
        }
    }

    private void logDuplicateSummary(DuplicateScanResult scanResult, Mode effectiveMode) {
        int groups = scanResult.duplicateGroupCount();
        int rows = scanResult.duplicateRowCount();
        log.warn(
                "H06 citation uniqueness {} scan: duplicateGroups={} duplicateRows={} uniqueIndexName={}",
                effectiveMode.name().toLowerCase(),
                groups,
                rows,
                migrationService.uniqueIndexName()
        );

        if (groups == 0) {
            return;
        }

        int safeSampleSize = Math.max(0, logSampleSize);
        scanResult.duplicatePairs().stream()
                .limit(safeSampleSize)
                .forEach(this::logDuplicateSample);
    }

    private void logDuplicateSample(DuplicatePair pair) {
        CitationUniquenessMigrationService.DedupeCandidate candidate = migrationService.buildCandidate(pair.ids());
        log.warn(
                "H06 citation duplicate pair detected: citedId={} citingId={} source={} ids={} keepId={}",
                pair.citedId(),
                pair.citingId(),
                pair.source(),
                pair.ids(),
                candidate.keptId()
        );
    }

    enum Mode {
        OFF,
        REPORT,
        APPLY;

        static Mode fromRaw(String raw) {
            if (raw == null) {
                return OFF;
            }
            String normalized = raw.trim().toLowerCase();
            return switch (normalized) {
                case "report" -> REPORT;
                case "apply" -> APPLY;
                default -> OFF;
            };
        }
    }
}
