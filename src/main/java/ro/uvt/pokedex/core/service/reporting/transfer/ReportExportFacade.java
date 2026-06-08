package ro.uvt.pokedex.core.service.reporting.transfer;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;

import java.util.Optional;

@Service
public class ReportExportFacade {

    private final IndividualReportRepository individualReportRepository;
    private final UserIndividualReportRunRepository userIndividualReportRunRepository;
    private final ReportInstanceSnapshotBuilder snapshotBuilder;
    private final ReportImportRegistry registry;
    private final UserIndicatorResultService userIndicatorResultService;
    private final ReportExportReadinessValidator readinessValidator;

    public ReportExportFacade(IndividualReportRepository individualReportRepository,
                              UserIndividualReportRunRepository userIndividualReportRunRepository,
                              ReportInstanceSnapshotBuilder snapshotBuilder,
                              ReportImportRegistry registry,
                              UserIndicatorResultService userIndicatorResultService,
                              ReportExportReadinessValidator readinessValidator) {
        this.individualReportRepository = individualReportRepository;
        this.userIndividualReportRunRepository = userIndividualReportRunRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.registry = registry;
        this.userIndicatorResultService = userIndicatorResultService;
        this.readinessValidator = readinessValidator;
    }

    public Optional<ExportedReport> exportRun(String userEmail, String reportId, ReportFormat format) {
        return exportRun(userEmail, reportId, format, false);
    }

    public Optional<ExportedReport> exportRun(String userEmail, String reportId, ReportFormat format, boolean forceRefresh) {
        return exportRun(userEmail, reportId, null, format, forceRefresh);
    }

    public Optional<ExportedReport> exportRun(String userEmail,
                                              String reportId,
                                              String runId,
                                              ReportFormat format,
                                              boolean forceRefresh) {
        ExportOutcome outcome = exportRunOutcome(userEmail, reportId, runId, format, forceRefresh);
        return outcome.isSuccess() ? Optional.of(outcome.exportedReport()) : Optional.empty();
    }

    public ExportOutcome exportRunOutcome(String userEmail,
                                          String reportId,
                                          String runId,
                                          ReportFormat format,
                                          boolean forceRefresh) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return ExportOutcome.failure(ExportFailureReason.REPORT_NOT_FOUND, "Report was not found.");
        }
        IndividualReport report = reportOpt.get();
        if (report.getReportTypeKey() == null || report.getReportTypeKey().isBlank()) {
            return ExportOutcome.failure(ExportFailureReason.REPORT_TYPE_NOT_CONFIGURED, "Report has no export template configured.");
        }

        Optional<ReportTypeImportSupport> supportOpt = registry.find(report.getReportTypeKey());
        if (supportOpt.isEmpty()) {
            return ExportOutcome.failure(ExportFailureReason.UNSUPPORTED_FORMAT, "Report type '" + report.getReportTypeKey() + "' is not registered.");
        }
        if (!supportOpt.get().supportedExportFormats().contains(format)) {
            return ExportOutcome.failure(ExportFailureReason.RENDERER_NOT_AVAILABLE,
                    "Report type is registered, but no " + format + " renderer is available.");
        }
        if (!readinessValidator.isReady(report, format)) {
            return ExportOutcome.failure(ExportFailureReason.NOT_READY, "Report export configuration is incomplete.");
        }

        if (forceRefresh && report.getIndicators() != null) {
            // Bypass the indicator-result cache so a scoring-service code change (which doesn't
            // bump the result's fingerprint) is picked up on the next export.
            for (Indicator indicator : report.getIndicators()) {
                if (indicator != null && indicator.getId() != null) {
                    userIndicatorResultService.refreshLatest(userEmail, indicator.getId());
                }
            }
        }

        RunResolution runResolution = resolveRun(userEmail, reportId, runId);
        if (!runResolution.isSuccess()) {
            return ExportOutcome.failure(runResolution.failureReason(), runResolution.message());
        }
        UserIndividualReportRun run = runResolution.run();

        ReportInstanceSnapshot snapshot = snapshotBuilder.build(run, report, userEmail);
        byte[] bytes = supportOpt.get().render(snapshot, format);

        String contentType = switch (format) {
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        };
        String extension = format.name().toLowerCase();
        String filename = sanitize(report.getTitle()) + "." + extension;
        return ExportOutcome.success(new ExportedReport(bytes, contentType, filename));
    }

    private RunResolution resolveRun(String userEmail, String reportId, String runId) {
        if (runId != null && !runId.isBlank()) {
            Optional<UserIndividualReportRun> runOpt = userIndividualReportRunRepository.findById(runId);
            if (runOpt.isEmpty()) {
                return RunResolution.failure(ExportFailureReason.RUN_NOT_FOUND, "Report run was not found.");
            }
            UserIndividualReportRun run = runOpt.get();
            if (!userEmail.equals(run.getUserEmail())) {
                return RunResolution.failure(ExportFailureReason.FORBIDDEN_RUN, "Report run belongs to another user.");
            }
            if (!reportId.equals(run.getReportDefinitionId())) {
                return RunResolution.failure(ExportFailureReason.RUN_REPORT_MISMATCH, "Report run does not belong to this report.");
            }
            return RunResolution.success(run);
        }
        return userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportId)
                .map(RunResolution::success)
                .orElseGet(() -> RunResolution.failure(ExportFailureReason.RUN_NOT_FOUND, "No report run exists for this report."));
    }

    private static String sanitize(String input) {
        if (input == null || input.isBlank()) return "report";
        return input.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    public record ExportedReport(byte[] bytes, String contentType, String filename) {}

    public enum ExportFailureReason {
        REPORT_NOT_FOUND,
        REPORT_TYPE_NOT_CONFIGURED,
        UNSUPPORTED_FORMAT,
        RENDERER_NOT_AVAILABLE,
        NOT_READY,
        RUN_NOT_FOUND,
        FORBIDDEN_RUN,
        RUN_REPORT_MISMATCH
    }

    public record ExportOutcome(ExportedReport exportedReport, ExportFailureReason failureReason, String message) {
        public boolean isSuccess() {
            return exportedReport != null;
        }

        public static ExportOutcome success(ExportedReport exportedReport) {
            return new ExportOutcome(exportedReport, null, null);
        }

        public static ExportOutcome failure(ExportFailureReason failureReason, String message) {
            return new ExportOutcome(null, failureReason, message);
        }
    }

    private record RunResolution(UserIndividualReportRun run, ExportFailureReason failureReason, String message) {
        boolean isSuccess() {
            return run != null;
        }

        static RunResolution success(UserIndividualReportRun run) {
            return new RunResolution(run, null, null);
        }

        static RunResolution failure(ExportFailureReason reason, String message) {
            return new RunResolution(null, reason, message);
        }
    }
}
