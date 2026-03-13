package ro.uvt.pokedex.core.controller.dto;

public record WosCategoryListItemResponse(
        String key,
        String categoryName,
        String edition,
        long journalCount,
        Integer latestYear
) {
}
