package ro.uvt.pokedex.core.service.application.model;

public record WosCategoryListItemViewModel(
        String key,
        String categoryName,
        String edition,
        long journalCount,
        Integer latestYear
) {
}
