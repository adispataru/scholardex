package ro.uvt.pokedex.core.controller.dto;

public record ScopusForumListItemResponse(
        String id,
        String publicationName,
        String issn,
        String eIssn,
        String aggregationType
) {
}
