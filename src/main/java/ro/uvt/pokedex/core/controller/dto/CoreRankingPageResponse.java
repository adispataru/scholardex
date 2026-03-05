package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record CoreRankingPageResponse(
        List<CoreRankingListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
