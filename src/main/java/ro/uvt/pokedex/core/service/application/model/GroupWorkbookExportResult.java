package ro.uvt.pokedex.core.service.application.model;

public record GroupWorkbookExportResult(
        byte[] workbookBytes,
        String contentType,
        String fileName
) {
}
