package ro.uvt.pokedex.core.service.reporting.transfer;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison;
import ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparisonService;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Read-only score verification for the H50.3 import flow: parse an uploaded report file, compare
 * its per-item scores against the displayed platform run, return the comparison. Never writes.
 */
@Service
public class ReportImportVerificationFacade {

    private final IndividualReportRepository individualReportRepository;
    private final UserIndividualReportRunRepository userIndividualReportRunRepository;
    private final ReportInstanceSnapshotBuilder snapshotBuilder;
    private final ReportImportRegistry registry;
    private final ReportScoreComparisonService comparisonService;
    private final ReportExportReadinessValidator readinessValidator;

    public ReportImportVerificationFacade(IndividualReportRepository individualReportRepository,
                                          UserIndividualReportRunRepository userIndividualReportRunRepository,
                                          ReportInstanceSnapshotBuilder snapshotBuilder,
                                          ReportImportRegistry registry,
                                          ReportScoreComparisonService comparisonService,
                                          ReportExportReadinessValidator readinessValidator) {
        this.individualReportRepository = individualReportRepository;
        this.userIndividualReportRunRepository = userIndividualReportRunRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.registry = registry;
        this.comparisonService = comparisonService;
        this.readinessValidator = readinessValidator;
    }

    public boolean isImportAvailable(String reportId) {
        return individualReportRepository.findById(reportId)
                .filter(IndividualReport::isImportEnabled)
                .filter(report -> readinessValidator.isReady(report, ReportFormat.XLSX))
                .map(r -> r.getReportTypeKey() != null
                        && registry.find(r.getReportTypeKey())
                                .map(s -> !s.supportedImportFormats().isEmpty())
                                .orElse(false))
                .orElse(false);
    }

    public Optional<ReportScoreComparison> verify(String userEmail, String reportId,
                                                  ReportFormat format, InputStream uploaded) {
        return verify(userEmail, reportId, null, format, uploaded)
                .map(VerificationResult::displayedRunComparison);
    }

    public Optional<VerificationResult> verify(String userEmail,
                                               String reportId,
                                               String runId,
                                               ReportFormat format,
                                               InputStream uploaded) {
        VerificationOutcome outcome = verifyOutcome(userEmail, reportId, runId, format, uploaded);
        return outcome.isSuccess() ? Optional.of(outcome.result()) : Optional.empty();
    }

    public VerificationOutcome verifyOutcome(String userEmail,
                                             String reportId,
                                             String runId,
                                             ReportFormat format,
                                             InputStream uploaded) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return VerificationOutcome.failure(VerificationFailureReason.REPORT_NOT_FOUND, "Report was not found.");
        }
        IndividualReport report = reportOpt.get();
        if (!report.isImportEnabled()) {
            return VerificationOutcome.failure(VerificationFailureReason.IMPORT_DISABLED, "Verify from file is not enabled for this report.");
        }
        if (report.getReportTypeKey() == null || report.getReportTypeKey().isBlank()) {
            return VerificationOutcome.failure(VerificationFailureReason.REPORT_TYPE_NOT_CONFIGURED, "Report has no template configured.");
        }
        if (!readinessValidator.isReady(report, format)) {
            return VerificationOutcome.failure(VerificationFailureReason.NOT_READY, "Report export/verify configuration is incomplete.");
        }

        Optional<ReportTypeImportSupport> supportOpt = registry.find(report.getReportTypeKey());
        if (supportOpt.isEmpty()) {
            return VerificationOutcome.failure(VerificationFailureReason.UNSUPPORTED_FORMAT, "Report type '" + report.getReportTypeKey() + "' is not registered.");
        }
        if (!supportOpt.get().supportedImportFormats().contains(format)) {
            return VerificationOutcome.failure(VerificationFailureReason.PARSER_NOT_AVAILABLE,
                    "Report type is registered, but no " + format + " parser is available.");
        }
        ReportTypeImportSupport support = supportOpt.get();

        // Parse the uploaded file first (consumes the stream). Layout deviations (researcher-
        // restructured templates with shifted columns) are collected as warnings for the UI —
        // the user is responsible for filling the official template.
        List<SnapshotItem> fileItems;
        List<String> layoutWarnings = new java.util.ArrayList<>();
        try {
            fileItems = support.parse(uploaded, format, layoutWarnings);
        } catch (RuntimeException ex) {
            return VerificationOutcome.failure(VerificationFailureReason.INVALID_WORKBOOK, "Could not read the uploaded workbook: " + ex.getMessage());
        }

        RunResolution displayedRunResolution = resolveRun(userEmail, reportId, runId);
        if (!displayedRunResolution.isSuccess()) {
            return VerificationOutcome.failure(displayedRunResolution.failureReason(), displayedRunResolution.message());
        }
        UserIndividualReportRun displayedRun = displayedRunResolution.run();
        ReportInstanceSnapshot displayedSnapshot = snapshotBuilder.build(displayedRun, report, userEmail);
        ReportScoreComparison displayedComparison = comparisonService.compare(displayedSnapshot.getItems(), fileItems);

        UserIndividualReportRun currentRun = userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportId)
                .filter(run -> !run.getId().equals(displayedRun.getId()))
                .orElse(null);
        ReportScoreComparison currentComparison = null;
        if (currentRun != null) {
            ReportInstanceSnapshot currentSnapshot = snapshotBuilder.build(currentRun, report, userEmail);
            currentComparison = comparisonService.compare(currentSnapshot.getItems(), fileItems);
        }

        return VerificationOutcome.success(new VerificationResult(
                displayedComparison,
                currentComparison,
                displayedRun.getId(),
                currentRun != null ? currentRun.getId() : null,
                List.copyOf(layoutWarnings)));
    }

    private RunResolution resolveRun(String userEmail, String reportId, String runId) {
        if (runId != null && !runId.isBlank()) {
            Optional<UserIndividualReportRun> runOpt = userIndividualReportRunRepository.findById(runId);
            if (runOpt.isEmpty()) {
                return RunResolution.failure(VerificationFailureReason.RUN_NOT_FOUND, "Report run was not found.");
            }
            UserIndividualReportRun run = runOpt.get();
            if (!userEmail.equals(run.getUserEmail())) {
                return RunResolution.failure(VerificationFailureReason.FORBIDDEN_RUN, "Report run belongs to another user.");
            }
            if (!reportId.equals(run.getReportDefinitionId())) {
                return RunResolution.failure(VerificationFailureReason.RUN_REPORT_MISMATCH, "Report run does not belong to this report.");
            }
            return RunResolution.success(run);
        }
        return userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportId)
                .map(RunResolution::success)
                .orElseGet(() -> RunResolution.failure(VerificationFailureReason.RUN_NOT_FOUND, "No report run exists for this report."));
    }

    public record VerificationResult(
            ReportScoreComparison displayedRunComparison,
            ReportScoreComparison currentRunComparison,
            String displayedRunId,
            String currentRunId,
            /** Layout-deviation notes (restructured template sheets that were not compared); never null. */
            List<String> layoutWarnings) {
        public VerificationResult {
            layoutWarnings = layoutWarnings == null ? List.of() : layoutWarnings;
        }
    }

    public enum VerificationFailureReason {
        REPORT_NOT_FOUND,
        IMPORT_DISABLED,
        REPORT_TYPE_NOT_CONFIGURED,
        UNSUPPORTED_FORMAT,
        PARSER_NOT_AVAILABLE,
        NOT_READY,
        RUN_NOT_FOUND,
        FORBIDDEN_RUN,
        RUN_REPORT_MISMATCH,
        INVALID_WORKBOOK
    }

    public record VerificationOutcome(VerificationResult result, VerificationFailureReason failureReason, String message) {
        public boolean isSuccess() {
            return result != null;
        }

        public static VerificationOutcome success(VerificationResult result) {
            return new VerificationOutcome(result, null, null);
        }

        public static VerificationOutcome failure(VerificationFailureReason reason, String message) {
            return new VerificationOutcome(null, reason, message);
        }
    }

    private record RunResolution(UserIndividualReportRun run, VerificationFailureReason failureReason, String message) {
        boolean isSuccess() {
            return run != null;
        }

        static RunResolution success(UserIndividualReportRun run) {
            return new RunResolution(run, null, null);
        }

        static RunResolution failure(VerificationFailureReason reason, String message) {
            return new RunResolution(null, reason, message);
        }
    }
}
