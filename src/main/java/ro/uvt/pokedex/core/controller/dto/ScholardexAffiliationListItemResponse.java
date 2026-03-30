package ro.uvt.pokedex.core.controller.dto;

public record ScholardexAffiliationListItemResponse(
        String afid,
        String name,
        String city,
        String country
) {
}
