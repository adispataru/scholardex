package ro.uvt.pokedex.core.service.application.model;

import java.time.Instant;

public record AdminActivityEvent(
        String type,
        String description,
        Instant timestamp,
        String link
) {
    public static final String TYPE_CONFLICT_RESOLUTION = "CONFLICT_RESOLUTION";
    public static final String TYPE_SCOPUS_SYNC = "SCOPUS_SYNC";
    public static final String TYPE_WOS_ENRICHMENT = "WOS_ENRICHMENT";
    public static final String TYPE_PUBLICATION_UPLOAD = "PUBLICATION_UPLOAD";
}
