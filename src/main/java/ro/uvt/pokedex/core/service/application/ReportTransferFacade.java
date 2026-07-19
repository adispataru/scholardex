package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportExportReadinessValidator;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportImportRegistry;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportTypeImportSupport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Z2 application facade over the Z3 report-transfer stack ({@link ReportExportFacade},
 * {@link ReportImportRegistry}, {@link ReportExportReadinessValidator}) for controller consumption —
 * controllers must not import {@code service.reporting} directly (Z1 → Z3 architecture boundary).
 * The export outcome is re-exposed as facade-owned types so the Z3 shapes never leak upward.
 */
@Service
@RequiredArgsConstructor
public class ReportTransferFacade {

    private final ReportExportFacade reportExportFacade;
    private final ReportImportRegistry reportImportRegistry;
    private final ReportExportReadinessValidator reportExportReadinessValidator;

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
