package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record ScholardexForumPageResponse(
        List<ScholardexForumListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
