package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationVenueClaim;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * H93 S2 — assembles the venue-claims section of the admin publication-decisions page and wraps the
 * service calls with the flash-message shape the page renders. Mirrors {@link PublicationMergeAdminFacade},
 * which owns the merges half of the same page.
 */
@Service
@RequiredArgsConstructor
public class PublicationVenueClaimAdminFacade {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final PublicationVenueClaimService claimService;
    private final ScholardexForumFactRepository forumFactRepository;
    private final ScholardexProjectionDirtyService projectionDirtyService;

    public ClaimQueueView queue() {
        List<Row> pending = new ArrayList<>();
        List<Row> approved = new ArrayList<>();
        List<Row> rejected = new ArrayList<>();
        for (PublicationVenueClaim claim : claimService.listClaims(null)) {
            Row row = toRow(claim);
            switch (claim.getStatus()) {
                case PENDING -> pending.add(row);
                case APPROVED -> approved.add(row);
                case REJECTED -> rejected.add(row);
            }
        }
        return new ClaimQueueView(pending, approved, rejected);
    }

    public OperationResult approve(String claimId, String decidedBy, String note, boolean rebuildProjectionsNow) {
        PublicationVenueClaimService.ClaimApplyResult result = claimService.approve(claimId, decidedBy, note);
        return describe(result, rebuildProjectionsNow);
    }

    public OperationResult directClaim(String publicationId, String forumId, boolean workshopOf,
                                       String workshopLabel, String decidedBy, String note,
                                       boolean rebuildProjectionsNow) {
        PublicationVenueClaimService.ClaimApplyResult result =
                claimService.directClaim(publicationId, forumId, workshopOf, workshopLabel, decidedBy, note);
        return describe(result, rebuildProjectionsNow);
    }

    public PublicationVenueClaim reject(String claimId, String decidedBy, String note) {
        return claimService.reject(claimId, decidedBy, note);
    }

    private OperationResult describe(PublicationVenueClaimService.ClaimApplyResult result,
                                     boolean rebuildProjectionsNow) {
        String projections = "projections marked dirty (run the rebuild from the Conflicts page, or re-run with 'rebuild now')";
        boolean failed = false;
        if (result.outcome() != PublicationVenueClaimService.ClaimOutcome.APPLIED) {
            projections = "no projection change";
        } else if (rebuildProjectionsNow) {
            ScholardexProjectionDirtyService.ProjectionRebuildResult rebuild =
                    projectionDirtyService.rebuildDirtyProjections();
            failed = rebuild.hasFailures();
            projections = failed ? "projection rebuild FAILED: " + rebuild.errors() : "projections rebuilt";
        }
        String message = switch (result.outcome()) {
            case APPLIED -> "Venue claim applied to " + result.publicationId() + "; " + projections
                    + ". Scores move on the next report refresh.";
            case PUBLICATION_NOT_FOUND -> "Skipped — the publication could not be resolved; nothing was changed.";
            case FORUM_NOT_FOUND -> "Skipped — the claimed forum no longer exists; nothing was changed.";
        };
        boolean success = result.outcome() == PublicationVenueClaimService.ClaimOutcome.APPLIED && !failed;
        return new OperationResult(success, message);
    }

    private Row toRow(PublicationVenueClaim claim) {
        String forumName = claim.getClaimedForumName();
        if (forumName == null && claim.getClaimedForumId() != null) {
            forumName = forumFactRepository.findById(claim.getClaimedForumId())
                    .map(ScholardexForumFact::getName)
                    .orElse(claim.getClaimedForumId());
        }
        String claimSummary = claim.isWorkshopOf()
                ? (claim.getWorkshopLabel() == null ? "workshop" : claim.getWorkshopLabel()) + " @ " + forumName
                : forumName;
        return new Row(
                claim.getId(),
                claim.getStatus().name(),
                claim.getPublicationId(),
                claim.getPublicationTitle(),
                claimSummary,
                claim.isWorkshopOf(),
                requestedInfo(claim),
                decidedInfo(claim),
                stamp(claim.getLastAppliedAt()),
                claim.getRequestNote(),
                claim.getDecisionNote()
        );
    }

    private static String requestedInfo(PublicationVenueClaim claim) {
        if (claim.getRequestedByEmail() == null && claim.getCreatedAt() == null) {
            return "—";
        }
        String who = claim.getRequestedByEmail() == null ? "admin" : claim.getRequestedByEmail();
        return who + " · " + stamp(claim.getCreatedAt());
    }

    private static String decidedInfo(PublicationVenueClaim claim) {
        if (claim.getDecidedBy() == null && claim.getDecidedAt() == null) {
            return "—";
        }
        return (claim.getDecidedBy() == null ? "?" : claim.getDecidedBy()) + " · " + stamp(claim.getDecidedAt());
    }

    private static String stamp(Instant instant) {
        return instant == null ? "—" : STAMP.format(instant);
    }

    public record ClaimQueueView(List<Row> pending, List<Row> approved, List<Row> rejected) {
    }

    public record Row(String id, String status, String publicationId, String publicationTitle,
                      String claimSummary, boolean workshopOf,
                      String requestedInfo, String decidedInfo, String lastAppliedAt,
                      String requestNote, String decisionNote) {
    }

    public record OperationResult(boolean success, String message) {
    }
}
