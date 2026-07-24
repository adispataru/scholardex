package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * H84 S2 — view assembly + operation delegation for the admin publication-merge queue. Each decision side is
 * shown from the LIVE canonical fact when it still exists (title/venue may have changed since the request) and
 * falls back to the decision's snapshot when it does not — a vanished duplicate is exactly what an applied
 * merge looks like, so the "gone" state is rendered as applied rather than as an error.
 */
@Service
@RequiredArgsConstructor
public class PublicationMergeAdminFacade {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final PublicationMergeService publicationMergeService;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexForumFactRepository forumFactRepository;
    private final ScholardexProjectionDirtyService projectionDirtyService;

    public MergeQueueView queue() {
        List<Row> pending = new ArrayList<>();
        List<Row> approved = new ArrayList<>();
        List<Row> rejected = new ArrayList<>();
        for (PublicationMergeDecision decision : publicationMergeService.listDecisions(null)) {
            Row row = toRow(decision);
            switch (decision.getStatus()) {
                case PENDING -> pending.add(row);
                case APPROVED -> approved.add(row);
                case REJECTED -> rejected.add(row);
            }
        }
        return new MergeQueueView(pending, approved, rejected);
    }

    public OperationResult approve(String decisionId, String decidedBy, String note,
                                   boolean swapSides, boolean rebuildProjectionsNow) {
        return describe(publicationMergeService.approve(decisionId, decidedBy, note, swapSides), rebuildProjectionsNow);
    }

    public OperationResult directMerge(String survivorId, String duplicateId, String decidedBy, String note,
                                       boolean rebuildProjectionsNow) {
        return describe(publicationMergeService.directMerge(survivorId, duplicateId, decidedBy, note), rebuildProjectionsNow);
    }

    public PublicationMergeDecision reject(String decisionId, String decidedBy, String note) {
        return publicationMergeService.reject(decisionId, decidedBy, note);
    }

    private OperationResult describe(PublicationMergeService.MergeApplyResult result, boolean rebuildProjectionsNow) {
        String projections = "projections marked dirty (run the rebuild from the Conflicts page, or re-run with 'rebuild now')";
        boolean failed = false;
        if (result.outcome() != PublicationMergeService.MergeOutcome.MERGED) {
            projections = "no projection change";
        } else if (rebuildProjectionsNow) {
            ScholardexProjectionDirtyService.ProjectionRebuildResult rebuild =
                    projectionDirtyService.rebuildDirtyProjections();
            failed = rebuild.hasFailures();
            projections = failed ? "projection rebuild FAILED: " + rebuild.errors() : "projections rebuilt";
        }
        String message = switch (result.outcome()) {
            case MERGED -> "Merged " + result.duplicateId() + " into " + result.survivorId()
                    + " (rows moved=" + result.rowsMoved() + ", deduplicated=" + result.rowsDeduplicated() + "); " + projections + ".";
            case ALREADY_MERGED -> "Nothing to do — the duplicate no longer exists (already merged).";
            case DUPLICATE_NOT_FOUND -> "Nothing to do — the duplicate could not be resolved.";
            case SURVIVOR_NOT_FOUND -> "Skipped — the SURVIVOR could not be resolved; nothing was changed.";
        };
        boolean success = result.outcome() != PublicationMergeService.MergeOutcome.SURVIVOR_NOT_FOUND && !failed;
        return new OperationResult(success, message);
    }

    private Row toRow(PublicationMergeDecision decision) {
        return new Row(
                decision.getId(),
                decision.getStatus().name(),
                sideView(decision.getSurvivor()),
                sideView(decision.getDuplicate()),
                requestedInfo(decision),
                decidedInfo(decision),
                stamp(decision.getLastAppliedAt()),
                decision.getRequestNote(),
                decision.getDecisionNote()
        );
    }

    private SideView sideView(PublicationMergeDecision.Side side) {
        ScholardexPublicationFact fact = side.getCanonicalId() == null
                ? null
                : publicationFactRepository.findById(side.getCanonicalId()).orElse(null);
        PublicationMergeDecision.Snapshot snapshot = side.getSnapshot();
        if (fact == null) {
            return new SideView(side.getCanonicalId(), false, blankToNull(snapshot.getTitle()), side.getSource(),
                    blankToNull(snapshot.getCoverDate()), blankToNull(snapshot.getDoi()), blankToNull(snapshot.getEid()),
                    snapshot.getCitedByCount(), null);
        }
        String forumName = fact.getForumId() == null
                ? null
                : forumFactRepository.findById(fact.getForumId())
                        .map(forum -> forum.getName())
                        .orElse(null);
        return new SideView(fact.getId(), true, blankToNull(fact.getTitle()), fact.getSource(),
                blankToNull(fact.getCoverDate()), blankToNull(fact.getDoi()), blankToNull(fact.getEid()),
                fact.getCitedByCount(), blankToNull(forumName));
    }

    private static String requestedInfo(PublicationMergeDecision decision) {
        if (decision.getRequestedByEmail() == null && decision.getCreatedAt() == null) {
            return "—";
        }
        String who = decision.getRequestedByEmail() == null ? "admin" : decision.getRequestedByEmail();
        return who + " · " + stamp(decision.getCreatedAt());
    }

    private static String decidedInfo(PublicationMergeDecision decision) {
        if (decision.getDecidedBy() == null && decision.getDecidedAt() == null) {
            return "—";
        }
        return (decision.getDecidedBy() == null ? "?" : decision.getDecidedBy()) + " · " + stamp(decision.getDecidedAt());
    }

    private static String stamp(Instant instant) {
        return instant == null ? "—" : STAMP.format(instant);
    }

    /** Empty strings in old records ("doi": "") must render as absent, not as a dangling label. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record MergeQueueView(List<Row> pending, List<Row> approved, List<Row> rejected) {
    }

    public record Row(String id, String status, SideView survivor, SideView duplicate,
                      String requestedInfo, String decidedInfo, String lastAppliedInfo,
                      String requestNote, String decisionNote) {
    }

    /** One side of the compare block; {@code exists=false} renders from the snapshot (an applied merge for duplicates). */
    public record SideView(String canonicalId, boolean exists, String title, String source, String coverDate,
                           String doi, String eid, Integer citedByCount, String forumName) {
    }

    public record OperationResult(boolean success, String message) {
    }
}
