package ro.uvt.pokedex.core.view.user;

/**
 * Lightweight run/snapshot descriptor surfaced to the individual-report view (compare picker,
 * prior-runs list). Extracted from {@code EvaluationWorkspaceController} so the shared
 * {@link IndividualReportViewModelAssembler} can build it for both the researcher's own page and
 * the delegated admin/supervisor view.
 */
public record RunSummary(String runId, String createdAt, String status, String name) {
}
