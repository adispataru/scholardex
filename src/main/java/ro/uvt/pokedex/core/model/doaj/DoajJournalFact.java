package ro.uvt.pokedex.core.model.doaj;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * H66 A4 — a journal listed in the DOAJ open-access snapshot, keyed by its normalized ISSN.
 *
 * <p>DOAJ carries no native forum identifier (unlike Scopus Source ID / WoS journalId): it contributes
 * <i>membership only</i>, matched to canonical forums by ISSN at projection time. This is reference data
 * imported from the DOAJ CSV via the admin endpoint, NOT derived from the source-replay ingest — so it is
 * owned but intentionally outside {@code PipelineRebuildService.MANAGED_DERIVED_COLLECTIONS} (it persists
 * across a full rebuild; re-import to refresh the snapshot). The projection re-reads it each rebuild to
 * emit {@code scholardex_forum_membership_view} rows with {@code database='DOAJ'}.
 */
@Data
@Document(collection = "doaj.journal_facts")
public class DoajJournalFact {

    /** Normalized primary ISSN (print preferred, else eISSN) — the stable identity for upsert. */
    @Id
    private String id;

    /** Normalized print ISSN (compact 8-char, no hyphen), or null. */
    private String issn;
    /** Normalized online ISSN (compact 8-char, no hyphen), or null. */
    private String eIssn;
    private String title;
    /** Snapshot stamp for the import (e.g. the DOAJ dump year); surfaces as membership_view.as_of. */
    private String asOf;
    private String source;
    private String sourceBatchId;
    private Instant createdAt;
    private Instant updatedAt;
}
