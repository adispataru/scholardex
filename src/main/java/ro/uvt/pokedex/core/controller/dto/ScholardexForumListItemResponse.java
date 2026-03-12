package ro.uvt.pokedex.core.controller.dto;

public record ScholardexForumListItemResponse(
        String id,
        String publicationName,
        String issn,
        String eIssn,
        String aggregationType,
        String wosStatus,
        String wosJournalId
) {
}
