package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record WosRankingPageResponse(
        List<WosRankingListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
