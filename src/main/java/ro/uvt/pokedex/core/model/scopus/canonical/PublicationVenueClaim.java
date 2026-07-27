package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * H93 — a human-supplied venue correction for one publication, durable across full rebuilds (SPARED
 * collection, like {@code publication_merge_decisions}). A researcher claims that a publication belongs to
 * a specific FORUM — optionally as "a workshop of" that forum's conference — an admin approves, and the
 * approved claim is applied live and re-applied after every full rebuild.
 *
 * <p><b>Claim beats DBLP.</b> An approved claim overrides machine-derived venue evidence: DBLP is an
 * archive guessing from volume names, a claim is a person who was there, reviewed by an admin. The values
 * a claim displaces are kept here so a later rejection can revert them exactly.
 *
 * <p><b>Anchoring.</b> {@code publicationId} is the working key (unique — one claim per publication), but
 * the DOI plus titleNormalized+year survive a rebuild re-minting the publication under a new id; the
 * re-apply pass re-resolves through them and re-anchors the claim (H89 measured ~0.2% of ids shifting).
 */
@Data
@Document(collection = "scholardex.publication_venue_claims")
@CompoundIndex(name = "uniq_scholardex_venue_claim_publication", def = "{'publicationId': 1}", unique = true)
public class PublicationVenueClaim {

    @Id
    private String id;
    private Status status;

    /** Working anchor — re-pointed by the re-apply pass when the publication is re-minted. */
    private String publicationId;
    /** Durable anchors, captured at request time. */
    private String doiNormalized;
    private String titleNormalized;
    private Integer year;
    /** For display in the approval queue without a join. */
    private String publicationTitle;

    /** The claimed venue. */
    private String claimedForumId;
    private String claimedForumName;
    /**
     * True = the publication is a WORKSHOP of the claimed forum's conference: evidence is written as
     * {@code <workshopLabel>@<ACRONYM>}, which the scorer's existing X@Y path turns into the half-points
     * ladder off the parent's CORE rank. False = the publication simply belongs to that forum.
     */
    private boolean workshopOf;
    private String workshopLabel;

    private String requestedByEmail;
    private String requestedByResearcherId;
    private String requestNote;
    private String decidedBy;
    private Instant decidedAt;
    private String decisionNote;

    /** What the claim overrode, captured ONCE on first apply — the exact revert target for a rejection. */
    private Displaced displaced;
    /** Stamped every time the claim applies (or verifies), live or via the rebuild re-apply pass. */
    private Instant lastAppliedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @Data
    public static class Displaced {
        private String forumId;
        /** Evidence fields as they were; {@code evidenceExisted=false} means the claim created the row. */
        private boolean evidenceExisted;
        private String evidenceSeries;
        private String evidenceConferenceName;
        private String evidenceMatchMethod;
    }
}
