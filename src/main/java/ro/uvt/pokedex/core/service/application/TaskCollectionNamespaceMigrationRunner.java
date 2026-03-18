package ro.uvt.pokedex.core.service.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.service.application.TaskCollectionNamespaceMigrationService.ApplyResult;
import ro.uvt.pokedex.core.service.application.TaskCollectionNamespaceMigrationService.CollectionReport;
import ro.uvt.pokedex.core.service.application.TaskCollectionNamespaceMigrationService.NamespaceReport;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TaskCollectionNamespaceMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskCollectionNamespaceMigrationRunner.class);

    private final TaskCollectionNamespaceMigrationService migrationService;
    private final String mode;
    private final boolean failOnPending;
    private final int logSampleSize;
    private final boolean deleteOldAfterCopy;

    public TaskCollectionNamespaceMigrationRunner(
            TaskCollectionNamespaceMigrationService migrationService,
            @Value("${h06.tasks.namespace.mode:off}") String mode,
            @Value("${h06.tasks.namespace.fail-on-pending:false}") boolean failOnPending,
            @Value("${h06.tasks.namespace.log-sample-size:20}") int logSampleSize,
            @Value("${h06.tasks.namespace.delete-old-after-copy:false}") boolean deleteOldAfterCopy
    ) {
        this.migrationService = migrationService;
        this.mode = mode;
        this.failOnPending = failOnPending;
        this.logSampleSize = logSampleSize;
        this.deleteOldAfterCopy = deleteOldAfterCopy;
    }

    @Override
    public void run(@NonNull String... args) {
        Mode effectiveMode = Mode.fromRaw(mode);
        if (effectiveMode == Mode.OFF) {
            return;
        }

        NamespaceReport preReport = migrationService.scanNamespace(logSampleSize);
        logNamespaceReport(effectiveMode, preReport);

        if (effectiveMode == Mode.REPORT) {
            if (failOnPending && preReport.hasPendingOldOnlyRows()) {
                throw new IllegalStateException("H06 task collection namespace report found pending old-only rows.");
            }
            return;
        }

        ApplyResult applyResult = migrationService.applyMigration(deleteOldAfterCopy);
        NamespaceReport postReport = migrationService.scanNamespace(logSampleSize);
        log.info(
                "H06 task namespace apply completed: copiedPublicationRows={} copiedCitationRows={} deletedOldPublicationRows={} deletedOldCitationRows={} pendingOldOnlyRows={}",
                applyResult.copiedPublicationRows(),
                applyResult.copiedCitationRows(),
                applyResult.deletedOldPublicationRows(),
                applyResult.deletedOldCitationRows(),
                postReport.totalOldOnlyRows()
        );

        if (postReport.hasPendingOldOnlyRows()) {
            throw new IllegalStateException("H06 task collection namespace apply verification failed.");
        }
    }

    private void logNamespaceReport(Mode mode, NamespaceReport report) {
        log.warn(
                "H06 task namespace {} scan: oldRows={} newRows={} oldOnlyRows={}",
                mode.name().toLowerCase(),
                report.totalOldRows(),
                report.totalNewRows(),
                report.totalOldOnlyRows()
        );
        logCollectionReport(report.publicationReport());
        logCollectionReport(report.citationReport());
    }

    private void logCollectionReport(CollectionReport report) {
        log.warn(
                "H06 task namespace collection scan: oldCollection={} newCollection={} oldCount={} newCount={} oldOnlyCount={} oldOnlySampleIds={}",
                report.oldCollection(),
                report.newCollection(),
                report.oldCount(),
                report.newCount(),
                report.oldOnlyCount(),
                report.oldOnlySampleIds()
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
