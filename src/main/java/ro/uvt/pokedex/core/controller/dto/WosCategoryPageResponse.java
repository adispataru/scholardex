package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record WosCategoryPageResponse(
        List<WosCategoryListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
