package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.reporting.IndividualReport;

import java.util.List;

/**
 * A criterion-level comparison between a researcher's latest runs of two "compatible" report
 * definitions (e.g. FV Info 2016 vs FV Info 2026) — see
 * {@link ro.uvt.pokedex.core.service.application.ReportComparisonFacade}.
 */
public record ReportComparisonViewModel(
        IndividualReport olderReport,
        IndividualReport newerReport,
        boolean olderRunAvailable,
        boolean newerRunAvailable,
        List<CriterionComparisonRow> rows,
        Double olderTotalScore,
        Double newerTotalScore,
        Double totalScoreDelta,
        /** Percent change vs olderTotalScore; null when olderTotalScore is zero (undefined) or totalScoreDelta is null. */
        Double totalScoreDeltaPercent,
        /** True when that side's latest run is an admin-provisional (declared-authorship) run. */
        boolean olderRunProvisional,
        boolean newerRunProvisional
) {

    /**
     * One comparison row, matched by criterion name across the two reports. Either score is null
     * when the criterion has no counterpart in that report (e.g. a 2026-only criterion); {@code delta}
     * (and {@code deltaPercent}) is null unless both sides are present.
     */
    public record CriterionComparisonRow(
            String name,
            Double olderScore,
            Double newerScore,
            Double delta,
            /** Percent change vs olderScore; null when olderScore is zero (undefined) or delta is null. */
            Double deltaPercent,
            Boolean olderMet,
            Boolean newerMet,
            boolean presentInOlder,
            boolean presentInNewer
    ) {
    }
}
