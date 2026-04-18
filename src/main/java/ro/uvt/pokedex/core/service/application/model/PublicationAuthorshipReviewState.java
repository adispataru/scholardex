package ro.uvt.pokedex.core.service.application.model;

import java.time.Instant;

public record PublicationAuthorshipReviewState(
        Status status,
        String reason,
        Instant updatedAt
) {
    public enum Status {
        PENDING,
        CONFIRMED,
        REJECTED
    }
}
