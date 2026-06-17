package ro.uvt.pokedex.core.model.erih;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H66 A5 — a journal in the ERIH PLUS snapshot, keyed by its native ERIH+ id.
 *
 * <p>Unlike DOAJ, ERIH+ carries a native id ({@code id}) that becomes a forum FK ({@code erihIds}) via
 * {@code ErihOnboardingService} (match-only by ISSN — see [[h66-curated-allowlists]]). This is reference
 * data imported from the ERIH PLUS Typesense export, owned but intentionally outside
 * {@code PipelineRebuildService.MANAGED_DERIVED_COLLECTIONS} (it persists across a full rebuild;
 * re-import + re-onboard to refresh). It also carries an independent open-access flag ({@code oaDoaj}).
 */
@Data
@Document(collection = "erih.journal_facts")
public class ErihJournalFact {

    /** Native ERIH+ id (e.g. "509625") — populated onto forums as an {@code erihIds} entry. */
    @Id
    private String id;

    /** Normalized print ISSN (compact 8-char, no hyphen), or null. */
    private String issn;
    /** Normalized online ISSN (compact 8-char, no hyphen), or null. */
    private String eIssn;
    private String title;
    /** ERIH+ discipline ids (numeric strings; name resolution deferred). */
    private List<String> disciplineIds = new ArrayList<>();
    /** ERIH PLUS's own open-access (DOAJ) flag for the journal. */
    private boolean oaDoaj;
    /** Snapshot stamp for the import (e.g. dump year); surfaces as membership_view.as_of. */
    private String asOf;
    private String source;
    private String sourceBatchId;
    private Instant createdAt;
    private Instant updatedAt;
}
