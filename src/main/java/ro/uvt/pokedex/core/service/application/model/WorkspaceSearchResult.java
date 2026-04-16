package ro.uvt.pokedex.core.service.application.model;

public record WorkspaceSearchResult(
        String id,
        EntityType entityType,
        String title,
        String subtitle,
        String url
) {
    public enum EntityType {
        PUBLICATION,
        ACTIVITY,
        CITATION
    }
}
