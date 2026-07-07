package ro.uvt.pokedex.core.view.user;

import java.util.Map;

/**
 * Prior-run entry for the evaluation page's inline JSON ({@code window.evalPriorRuns}). Unlike
 * {@link RunSummary} (compare picker/endpoints — kept lean), this carries the per-criterion
 * scores so the client can chart score trends across runs without an extra endpoint.
 */
public record PriorRunView(String runId, String createdAt, String status,
                           Map<Integer, Double> criteriaScores) {
}
