package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;

import java.time.Instant;

public record PublicationAuthorshipDecisionAdminSummary(
        int totalDecisions,
        int confirmedCount,
        int rejectedCount,
        PublicationAuthorshipDecision.Status latestStatus,
        Instant latestUpdatedAt
) {
}
