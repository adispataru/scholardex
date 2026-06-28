package ro.uvt.pokedex.core.controller.dto;

/**
 * H64 slice 2 — one canonical project in the workspace picker / reference resolution. Carries enough to disambiguate
 * in the dropdown ("code — title (funder, years)") and to show the chosen reference as a human label.
 */
public record ScholardexProjectListItemResponse(
        String id,
        String code,
        String euGrantId,
        String title,
        String funder,
        String director,
        Integer startYear,
        Integer endYear,
        String coordinatorName,
        Long budget
) {
}
