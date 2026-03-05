package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record ScopusAuthorPageResponse(
        List<ScopusAuthorListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
