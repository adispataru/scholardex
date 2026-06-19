package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H66B Phase 4a — durable OpenAlex publication source-fact. Mirrors {@link UserDefinedPublicationFact}: a
 * separate, replayable source table (NOT {@code scopus.publication_facts}) so OpenAlex-origin publications
 * survive a full rebuild. The on-demand ORCID sync upserts these (idempotent on the OpenAlex work id) and
 * {@code OpenAlexCanonicalizationService} resolves them to canonical {@link ScholardexPublicationFact} rows
 * (DOI-collision links onto an existing pub via Decision 0; otherwise mints).
 */
@Data
@Document(collection = "openalex.publication_facts")
@CompoundIndex(name = "uniq_openalex_publication_source_record_id", def = "{'sourceRecordId': 1}", unique = true)
public class OpenAlexPublicationFact {
    @Id
    private String id;

    // Provenance / lineage
    private String source;
    private String sourceRecordId;   // == openalexWorkId (the stable OpenAlex work id, e.g. W2741809807)
    private String sourceEventId;
    private String sourceBatchId;
    private String sourceCorrelationId;

    // Identity + bibliographic content
    private String openalexWorkId;
    private String doi;
    private String title;
    private Integer publicationYear;
    private String coverDate;        // derived "YYYY-01-01" for buildCanonicalPublicationId parity
    private String creator;          // first authorship display name
    private String type;             // OpenAlex work type (journal-article, book-chapter, ...)
    private Integer authorCount;
    private Integer citedByCount;
    private Boolean openAccess;

    /** Co-author display names (Stage 1 stores them; canonical co-author bridging is deferred). */
    private List<String> authorDisplayNames = new ArrayList<>();
    /** Co-author ORCIDs where OpenAlex provides them (deferred bridging input). */
    private List<String> authorOrcids = new ArrayList<>();
    /** Display names of authorships OpenAlex flags is_corresponding (sparse; populates minted pubs' correspondingAuthors). */
    private List<String> correspondingAuthorNames = new ArrayList<>();

    // Venue (Stage 3 — ofOpenAlex forum resolve)
    private String hostVenueName;
    private List<String> hostVenueIssns = new ArrayList<>();

    // Citations (Stage 2 — DOI/OpenAlex-id edges)
    private List<String> referencedWorks = new ArrayList<>();

    /**
     * Canonical author ids of platform researchers who synced this work via their own ORCID. The
     * canonicalization attaches one authorship edge per id, so a minted work is visible in the researcher's
     * workspace without resolving OpenAlex's fragmented author entities. Append-only across syncs.
     */
    private List<String> syncedResearcherAuthorIds = new ArrayList<>();

    private String lastPayloadHash;
    private Instant lastMaterializedAt;
    private Instant createdAt;
    private Instant updatedAt;
    /** Builder-logic version that produced this fact. */
    private String builderVersion;
}
