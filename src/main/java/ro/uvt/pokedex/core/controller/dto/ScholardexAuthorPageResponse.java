package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record ScholardexAuthorPageResponse(
        List<ScholardexAuthorListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
