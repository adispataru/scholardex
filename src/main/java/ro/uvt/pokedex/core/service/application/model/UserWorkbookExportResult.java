package ro.uvt.pokedex.core.service.application.model;

public record UserWorkbookExportResult(
        UserWorkbookExportStatus status,
        byte[] workbookBytes,
        String contentType,
        String fileName
) {
    public static UserWorkbookExportResult ok(byte[] workbookBytes, String contentType, String fileName) {
        return new UserWorkbookExportResult(UserWorkbookExportStatus.OK, workbookBytes, contentType, fileName);
    }

    public static UserWorkbookExportResult unauthorized() {
        return new UserWorkbookExportResult(UserWorkbookExportStatus.UNAUTHORIZED, null, null, null);
    }

    public static UserWorkbookExportResult notFound() {
        return new UserWorkbookExportResult(UserWorkbookExportStatus.NOT_FOUND, null, null, null);
    }
}
