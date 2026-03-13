package ro.uvt.pokedex.core.service.application.model;

import java.util.List;

public record WosCategoryDetailViewModel(
        String key,
        String categoryName,
        String edition,
        long journalCount,
        Integer latestYear,
        List<WosCategoryJournalViewModel> journals
) {
}
