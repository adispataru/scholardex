package ro.uvt.pokedex.core.controller.dto;

import java.util.List;

public record WosRankingListItemResponse(
        String id,
        String name,
        String issn,
        String eIssn,
        List<String> alternativeIssns
) {
}
