package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record ScopusForumPageResponse(
        List<ScopusForumListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
