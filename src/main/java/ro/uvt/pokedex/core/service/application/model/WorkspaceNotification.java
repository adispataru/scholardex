package ro.uvt.pokedex.core.service.application.model;

public record WorkspaceNotification(
        String id,
        NotificationType type,
        String title,
        String body,
        String occurredAt,
        String actionUrl
) {
    public enum NotificationType {
        NEW_CITATION,
        SYNC_COMPLETED,
        REPORT_AVAILABLE,
        PROFILE_INCOMPLETE
    }
}
