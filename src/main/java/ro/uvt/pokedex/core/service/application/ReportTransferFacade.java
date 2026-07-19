package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportExportReadinessValidator;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportImportRegistry;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportImportVerificationFacade;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportTypeImportSupport;
import ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Z2 application facade over the Z3 report-transfer stack ({@link ReportExportFacade},
 * {@link ReportImportRegistry}, {@link ReportExportReadinessValidator}, {@link ReportImportVerificationFacade}) for controller consumption —
 * controllers must not import {@code service.reporting} directly (Z1 → Z3 architecture boundary).
 * The export outcome is re-exposed as facade-owned types so the Z3 shapes never leak upward.
 */
@Service
@RequiredArgsConstructor
public class ReportTransferFacade {

    private final ReportExportFacade reportExportFacade;
    private final ReportImportRegistry reportImportRegistry;
    private final ReportExportReadinessValidator reportExportReadinessValidator;
    private final ReportImportVerificationFacade reportImportVerificationFacade;

    // ── Export ───────────────────────────────────────────────────────────────

    public record ExportedReport(byte[] bytes, String contentType, String filename) {}

    /** Mirrors {@link ReportExportFacade.ExportFailureReason} 1:1 (mapped by name below). */
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

        public static ExportOutcome failure(ExportFailureReason reason, String message) {
            return new ExportOutcome(null, reason, message);
        }
    }

    public ExportOutcome exportRunOutcome(String userEmail,
                                          String reportId,
                                          String runId,
                                          ReportFormat format,
                                          boolean forceRefresh) {
        ReportExportFacade.ExportOutcome outcome =
                reportExportFacade.exportRunOutcome(userEmail, reportId, runId, format, forceRefresh);
        if (outcome.isSuccess()) {
            ReportExportFacade.ExportedReport exported = outcome.exportedReport();
            return new ExportOutcome(
                    new ExportedReport(exported.bytes(), exported.contentType(), exported.filename()), null, null);
        }
        return new ExportOutcome(null, ExportFailureReason.valueOf(outcome.failureReason().name()), outcome.message());
    }

    /**
     * The export format the report type drives (XLSX → Excel, DOCX → Word), preferring XLSX when a
     * type supports both; XLSX when the type declares nothing.
     */
    public ReportFormat preferredExportFormat(String reportTypeKey) {
        return reportImportRegistry.find(reportTypeKey)
                .map(ReportTypeImportSupport::supportedExportFormats)
                .filter(formats -> !formats.isEmpty())
                .map(formats -> formats.contains(ReportFormat.XLSX) ? ReportFormat.XLSX : formats.iterator().next())
                .orElse(ReportFormat.XLSX);
    }

    // ── Import: score verification against an uploaded file ──────────────────

    /** Mirrors {@link ReportImportVerificationFacade.VerificationFailureReason} 1:1 (mapped by name below). */
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

    /**
     * The verification view payload. The two comparison objects are template-facing model attributes the
     * controller never inspects, so they stay {@code Object} here — the one structural read the controller
     * needed (which activity ids the comparison references, for the inline add-form) is pre-extracted into
     * {@code referencedActivityIds}.
     */
    public record VerificationView(Object displayedRunComparison,
                                   Object currentRunComparison,
                                   String displayedRunId,
                                   String currentRunId,
                                   List<String> layoutWarnings,
                                   List<String> referencedActivityIds) {}

    public record VerificationOutcome(VerificationView view, VerificationFailureReason failureReason, String message) {
        public boolean isSuccess() {
            return view != null;
        }

        public static VerificationOutcome success(VerificationView view) {
            return new VerificationOutcome(view, null, null);
        }

        public static VerificationOutcome failure(VerificationFailureReason reason, String message) {
            return new VerificationOutcome(null, reason, message);
        }
    }

    public boolean isImportAvailable(String reportId) {
        return reportImportVerificationFacade.isImportAvailable(reportId);
    }

    public VerificationOutcome verifyRun(String userEmail,
                                         String reportId,
                                         String runId,
                                         ReportFormat format,
                                         java.io.InputStream uploaded) {
        ReportImportVerificationFacade.VerificationOutcome outcome =
                reportImportVerificationFacade.verifyOutcome(userEmail, reportId, runId, format, uploaded);
        if (!outcome.isSuccess()) {
            return VerificationOutcome.failure(
                    VerificationFailureReason.valueOf(outcome.failureReason().name()), outcome.message());
        }
        ReportImportVerificationFacade.VerificationResult result = outcome.result();
        return VerificationOutcome.success(new VerificationView(
                result.displayedRunComparison(),
                result.currentRunComparison(),
                result.displayedRunId(),
                result.currentRunId(),
                result.layoutWarnings(),
                referencedActivityIds(result.displayedRunComparison())));
    }

    /** Ordered, de-duplicated activity ids referenced by the comparison's activity blocks. */
    private static List<String> referencedActivityIds(ReportScoreComparison comparison) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (comparison == null || comparison.activityBlocks() == null) {
            return List.of();
        }
        comparison.activityBlocks().forEach(block -> {
            if (block.activityOptions() == null) {
                return;
            }
            block.activityOptions().forEach(opt -> {
                if (opt.activityId() != null) {
                    ids.add(opt.activityId());
                }
            });
        });
        return List.copyOf(ids);
    }

    // ── Import-type registry lookups (admin report editor) ───────────────────

    public Set<String> registeredReportTypeKeys() {
        return reportImportRegistry.registeredKeys();
    }

    public List<String> declaredRoles(String reportTypeKey) {
        return findSupport(reportTypeKey).map(ReportTypeImportSupport::declaredRoles).orElse(List.of());
    }

    public Map<String, List<String>> declaredBlocksByRole(String reportTypeKey) {
        return findSupport(reportTypeKey).map(ReportTypeImportSupport::declaredBlocksByRole).orElse(Map.of());
    }

    private Optional<ReportTypeImportSupport> findSupport(String reportTypeKey) {
        return reportImportRegistry.find(reportTypeKey);
    }

    // ── Export readiness (admin report editor) ───────────────────────────────

    public String excludedFromTemplateValue() {
        return ReportExportReadinessValidator.EXCLUDED_FROM_TEMPLATE;
    }

    public List<String> validateExportReadiness(IndividualReport report, ReportFormat format) {
        return reportExportReadinessValidator.validate(report, format);
    }
}
