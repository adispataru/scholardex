package ro.uvt.pokedex.core.controller.dto;

public record ScopusAffiliationListItemResponse(
        String afid,
        String name,
        String city,
        String country
) {
}
