package ro.uvt.pokedex.core.service.application.model;

public record ForumExportRow(
        String publicationName,
        String issn,
        String eIssn,
        String sourceId,
        String aggregationType
) {
}
