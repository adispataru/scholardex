package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record ScholardexAffiliationPageResponse(
        List<ScholardexAffiliationListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
