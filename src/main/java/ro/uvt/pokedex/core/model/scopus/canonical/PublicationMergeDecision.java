package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H84 — a human-decided publication merge, durable across full rebuilds (SPARED collection, like
 * {@code scholardex.publication_dblp_evidence}). Created PENDING by a researcher flag (or directly APPROVED by an
 * admin merge), applied by {@code PublicationMergeService} both live (on approval) and as a re-apply pass chained
 * into the full-maintenance materialization — a rebuild re-mints both publications from source and the pass
 * re-merges them. REJECTED decisions are kept to suppress re-suggesting the same pair.
 *
 * <p>Sides are anchored on source-record refs ({@code SOURCE:recordId}) rather than canonical ids alone: canonical
 * ids are deterministic today, but a ref survives an identity-material change upstream (e.g. Scopus later adding a
 * DOI to the record).</p>
 */
@Data
@Document(collection = "scholardex.publication_merge_decisions")
@CompoundIndex(name = "uniq_publication_merge_pair", def = "{'pairKey': 1}", unique = true)
public class PublicationMergeDecision {

    @Id
    private String id;
    /** Unordered pair key: the two canonical ids at request time, sorted and joined with '|'. */
    private String pairKey;
    private Status status;
    private Side survivor = new Side();
    private Side duplicate = new Side();
    private String requestedByEmail;
    private String requestedByResearcherId;
    private String requestNote;
    private String decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private IdentityHint identityHint = new IdentityHint();
    /** Stamped every time the executor applies (or verifies) this decision — rebuild-re-apply observability. */
    private Instant lastAppliedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @Data
    public static class Side {
        private String canonicalId;
        /** Owning source of the publication at request time (SCOPUS / OPENALEX / USER_DEFINED …). */
        private String source;
        /** Durable anchors, each {@code SOURCE:recordId} (e.g. {@code SCOPUS:2-s2.0-83155184718}). */
        private List<String> sourceRecordRefs = new ArrayList<>();
        private Snapshot snapshot = new Snapshot();
    }

    @Data
    public static class Snapshot {
        private String title;
        private String eid;
        private String doi;
        private String coverDate;
        private Integer citedByCount;
    }

    /** Last-resort re-resolution key (and the auto-suggest key): exact normalized title + creator, year tolerant. */
    @Data
    public static class IdentityHint {
        private String titleNormalized;
        private Integer coverYear;
        private String creatorNormalized;
    }

    public static String pairKeyOf(String idA, String idB) {
        String a = idA == null ? "" : idA;
        String b = idB == null ? "" : idB;
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
