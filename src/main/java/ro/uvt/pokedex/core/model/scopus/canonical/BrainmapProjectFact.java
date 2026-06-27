package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * H64 slice 1 — a brainmap <b>project</b> source fact (RO national + the FP7 EU tail), from
 * {@code data/brainmap/uvt_projects.jsonl}. The only programmatic source for Romanian national (UEFISCDI/PN-III)
 * projects (OpenAIRE has zero RO national; CORDIS is EU-only — see the H64 task doc).
 *
 * <p>Stores the <b>raw</b> scraped fields (code, title, plan, competition, director person+role, coordinator display
 * name, funder, years) so the canonical derivation — merge-key choice, coordinator→affiliation resolution — lives in
 * the builder, not at ingest. Keyed by the brainmap internal project id ({@code pkXProiectId}) so a re-import upserts
 * in place. No budget (brainmap doesn't expose it).
 */
@Data
@Document(collection = "brainmap.project_facts")
public class BrainmapProjectFact {

    /** brainmap internal project id ({@code pkXProiectId}), e.g. {@code "8"}. */
    @Id
    private String id;

    private String code;
    private String title;
    private String plan;
    private String competition;

    private String directorFirst;
    private String directorLast;
    private String directorRole;

    private String coordinator;
    private String funder;

    private String startYear;
    private String endYear;

    private String detailHref;
    private String orgId;
    private String scrapedAt;

    private String source;
    private String sourceRecordId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;
    private String builderVersion;
}
