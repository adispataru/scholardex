package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H64 slice 1 — the canonical <b>project</b> entity, unifying project identity + attribution across sources. Built
 * from {@code brainmap.project_facts} (RO national + the FP7 EU tail) now; CORDIS + user-defined sources merge in
 * later by EU grant id.
 *
 * <p>Identity / merge key: {@link #euGrantId} (the brainmap code's trailing numeric segment, set only for EC-funded
 * projects) when present, else the brainmap {@link #code}. The canonical id is {@code sproj_<hash(key)>} so the same
 * EU project arriving from brainmap and CORDIS collapses onto one record.
 *
 * <p><b>No budget here.</b> brainmap doesn't expose it and CORDIS budget is org-level — A10's € stays declared on the
 * activity. The director person is captured raw ({@code director*}); resolving it to a {@code ResearcherProfile} is a
 * later enrichment slice (researchers self-link via the picker first).
 */
@Data
@Document(collection = "scholardex.project_facts")
public class ScholardexProjectFact {

    /** {@code sproj_<hash(euGrantId|code)>}. */
    @Id
    private String id;

    /** brainmap source project ids ({@code pkXProiectId}) that contributed to this canonical record. */
    private List<String> brainmapProjectIds = new ArrayList<>();
    /** user-defined source project ids (admin/CORDIS) that contributed (H64 slice 4b). */
    private List<String> userDefinedProjectIds = new ArrayList<>();
    /** Where {@link #budget} came from: {@code CORDIS} / {@code MANUAL} (null when no budget). H64 slice 4b. */
    private String budgetSource;

    /** EU grant id (CORDIS {@code ID}); the cross-source merge key for EC-funded projects. Null for RO national. */
    private String euGrantId;

    private String code;
    private String title;
    private String titleNormalized;
    private String plan;
    private String competition;
    private String funder;

    private String directorFirst;
    private String directorLast;
    private String directorRole;

    /** Lead-organization display name as scraped (kept for audit even when resolution fails). */
    private String coordinatorName;
    /** Canonical {@link ScholardexAffiliationFact} id the coordinator resolved to (ROR matcher), or null. */
    private String coordinatorAffiliationId;
    /** Canonical affiliation ids for partners (empty from the brainmap list view; populated from detail/CORDIS later). */
    private List<String> partnerAffiliationIds = new ArrayList<>();

    private Integer startYear;
    private Integer endYear;

    /** Always null from brainmap (no budget); declared on the activity or sourced from CORDIS/user later. */
    private Long budget;

    private String source;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;
    private String builderVersion;
}
