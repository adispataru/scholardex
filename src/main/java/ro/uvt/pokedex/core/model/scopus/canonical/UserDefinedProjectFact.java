package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * H64 slice 4b — an admin/CORDIS-entered <b>project</b> source fact. The trusted-budget source: brainmap carries no
 * budget, so the per-partner € for EU projects (CORDIS {@code ecContribution}) and any manually-entered budget live
 * here. Merged into the canonical {@link ScholardexProjectFact} by EU grant id (else code) in
 * {@code ProjectCanonicalizationService}, where user-defined WINS for budget and fills gaps.
 *
 * <p><b>Precious source — never wiped by a rebuild</b> (NOT in the managed/canonical wipe sets, unlike
 * {@link UserDefinedPublicationFact}); hand-entered budgets must survive even a full from-scratch rebuild. The
 * canonical layer is re-derived from it each run.
 */
@Data
@Document(collection = "user_defined.project_facts")
public class UserDefinedProjectFact {

    /** Stable id; for CORDIS entries use the EU grant id, for manual entries a minted id. */
    @Id
    private String id;

    /** EU grant id (CORDIS {@code ID}) — the cross-source merge key for EC-funded projects; null for RO-only manual. */
    private String euGrantId;
    private String code;
    private String title;
    private String funder;

    /** The trusted budget in whole currency units (e.g. EUR for CORDIS ecContribution). The point of this fact. */
    private Long budget;
    private String currency;

    private String directorFirst;
    private String directorLast;
    private String coordinatorName;
    private Integer startYear;
    private Integer endYear;

    /** Provenance of this entry: {@code CORDIS} (fetched) or {@code MANUAL} (admin-typed). */
    private String origin;

    // Review / submitter lineage (mirrors UserDefinedPublicationFact).
    private Boolean approved;
    private String reviewState;
    private String submitterEmail;
    private Instant submittedAt;

    private String source;
    private String sourceRecordId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;
    private String builderVersion;
}
