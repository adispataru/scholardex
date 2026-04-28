package ro.uvt.pokedex.core.controller.dto;

public record ArtisticEventTableItemResponse(
        String id,
        String name,
        String rank,
        String rankLabel,
        String domainId
) {
}
