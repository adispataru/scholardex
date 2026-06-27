package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record ScholardexProjectPageResponse(
        List<ScholardexProjectListItemResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
