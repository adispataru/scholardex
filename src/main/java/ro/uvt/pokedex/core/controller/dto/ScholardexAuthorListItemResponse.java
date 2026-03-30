package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record ScholardexAuthorListItemResponse(
        String id,
        String name,
        List<String> affiliations
) {
}
