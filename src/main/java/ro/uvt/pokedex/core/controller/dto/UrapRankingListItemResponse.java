package ro.uvt.pokedex.core.controller.dto;

public record UrapRankingListItemResponse(
        String id,
        String name,
        String country,
        Integer year,
        Integer rank,
        Double article,
        Double citation,
        Double totalDocument,
        Double ait,
        Double cit,
        Double collaboration,
        Double total
) {
}
