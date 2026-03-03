package ro.uvt.pokedex.core.service.application.model;

public record UserIndicatorWorkbookExportViewModel(
        byte[] workbookBytes,
        String contentType,
        String fileName
) {
}
