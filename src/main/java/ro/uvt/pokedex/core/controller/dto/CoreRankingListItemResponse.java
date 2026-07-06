package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record CoreRankingListItemResponse(
        String id,
        String name,
        String acronym,
        String latestRank,
        Integer latestYear,
        List<TrendPoint> trend
) {
    /** One CORE edition on the rank-trend sparkline: {@code value} is the numeric tier (higher = better),
     *  {@code label} the human rank (A*, A, ...) for tooltips. */
    public record TrendPoint(int year, int value, String label) {
    }
}
