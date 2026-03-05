package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record ScopusAffiliationPageResponse(
        List<ScopusAffiliationListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
