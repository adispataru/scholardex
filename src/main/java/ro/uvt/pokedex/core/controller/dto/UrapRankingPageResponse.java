package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record UrapRankingPageResponse(
        List<UrapRankingListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
