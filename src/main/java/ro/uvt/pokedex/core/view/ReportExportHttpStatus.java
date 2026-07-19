package ro.uvt.pokedex.core.view;

import org.springframework.http.HttpStatus;
import ro.uvt.pokedex.core.service.application.ReportTransferFacade.ExportFailureReason;

/**
 * Maps a {@link ExportFailureReason} to the HTTP status an export endpoint should return. Shared by
 * the researcher's own export ({@code EvaluationWorkspaceController}) and the delegated export
 * ({@code ResearcherReportController}) so the two stay in lock-step; the switch is exhaustive, so a
 * new failure reason is a compile error here rather than a silent 500 at one call site.
 */
public final class ReportExportHttpStatus {

    private ReportExportHttpStatus() {
    }

    public static HttpStatus of(ExportFailureReason reason) {
        return switch (reason) {
            case REPORT_NOT_FOUND, RUN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN_RUN -> HttpStatus.FORBIDDEN;
            case REPORT_TYPE_NOT_CONFIGURED, UNSUPPORTED_FORMAT -> HttpStatus.BAD_REQUEST;
            case RENDERER_NOT_AVAILABLE -> HttpStatus.NOT_IMPLEMENTED;
            case NOT_READY, RUN_REPORT_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
