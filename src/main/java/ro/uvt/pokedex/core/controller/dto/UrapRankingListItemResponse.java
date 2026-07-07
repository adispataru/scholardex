package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record UrapRankingListItemResponse(
        String id,
        String name,
        String country,
        Integer year,
        Integer rank,
        Double total,
        List<TrendPoint> trend
) {
    /** One URAP edition on the trend sparkline — both metrics travel together so the
     *  Rank | Total Score toggle re-renders client-side without a refetch. */
    public record TrendPoint(int year, Integer rank, Double total) {
    }
}
