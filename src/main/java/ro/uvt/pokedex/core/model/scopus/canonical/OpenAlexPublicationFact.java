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
    private Boolean retracted;       // OpenAlex is_retracted — research-ethics gate (retracted must not score)

    // Bibliographic detail (export completeness) — OpenAlex biblio.{volume,issue,first_page,last_page}
    private String volume;
    private String issue;
    private String firstPage;
    private String lastPage;

    // Impact metrics — OpenAlex fwci (field-weighted citation impact) + citation_normalized_percentile.value
    private Double fwci;
    private Double citationNormalizedPercentile;

    // Subject/domain signal — OpenAlex primary_topic
    private String primaryTopicId;
    private String primaryTopicName;

    /**
     * The work's full author list in OpenAlex order, with the identity needed to (a) resolve corresponding authors
     * to canonical authors and (b) positionally bridge ORCIDs onto the matching Scopus authors of a DOI-linked pub
     * (Scopus and OpenAlex agree on author order — validated 29/29). H66B Phase 4a.
     */
    private List<AuthorRef> authorships = new ArrayList<>();

    // Venue (Stage 3 — ofOpenAlex forum resolve)
    private String hostVenueName;
    private String hostVenueOpenAlexId; // bare OpenAlex source id (S…) of the host venue — Stage 3 forum resolve key
    private List<String> hostVenueIssns = new ArrayList<>();
    private String hostVenueSourceType; // OpenAlex source.type (journal|conference|book series|…) — venue-kind signal
    private String hostVenuePublisher;  // OpenAlex source.host_organization_name — SENSE book-classification key

    // Citations (Stage 2 — DOI/OpenAlex-id edges)
    private List<String> referencedWorks = new ArrayList<>();

    /**
     * Incoming citers — the OpenAlex work ids that cite THIS work (Ext A completeness). Populated only on synced
     * researcher works, by a per-paper {@code cites:} query so attribution is exact. Stored durably so the
     * citation edge build re-derives incoming edges on a full rebuild without re-fetching — this captures the
     * citers whose own {@code referenced_works} omit this paper (OpenAlex cites-index ⊋ stored reference lists).
     */
    private List<String> citedByWorkIds = new ArrayList<>();

    /**
     * Platform researchers who synced this work via their own ORCID — canonical author id + the ORCID that drove
     * the sync. The canonicalization attaches one authorship edge per researcher (visibility) and uses the ORCID to
     * (a) seed it onto the canonical author and (b) dedup the researcher-is-corresponding case. Append-only;
     * durable so the full-rebuild replay can re-seed + re-resolve. Replaces the prior id-only list.
     */
    private List<SyncedResearcher> syncedResearchers = new ArrayList<>();

    /** One OpenAlex authorship in author order, id-resolvable. */
    @Data
    public static class AuthorRef {
        private int position;            // 0-based author order (for the positional ORCID bridge)
        private String displayName;
        private String orcid;            // normalized bare, when OpenAlex provides it
        private String openAlexAuthorId; // A…, the OpenAlex author entity id
        private boolean corresponding;   // OpenAlex is_corresponding flag
        // Affiliation (H71): the signal the author reconciler needs to dedup co-authors across sources.
        private List<String> institutionNames = new ArrayList<>(); // OpenAlex-resolved org display names
        private List<String> rawAffiliations = new ArrayList<>();   // verbatim affiliation strings on the paper
        private String countryCode;                                 // primary authorship country (ISO alpha-2)
        // H72 slice 3a: bare ROR ids (e.g. "03r6neh61") of this authorship's institutions — the cross-source
        // affiliation key bridged onto verified Scopus affiliations on DOI-linked pubs.
        private List<String> institutionRors = new ArrayList<>();
    }

    /** A platform researcher who synced this work, with the identity needed to seed/dedup. */
    @Data
    public static class SyncedResearcher {
        private String canonicalAuthorId;
        private String orcid;
    }

    private String lastPayloadHash;
    private Instant lastMaterializedAt;
    private Instant createdAt;
    private Instant updatedAt;
    /** Builder-logic version that produced this fact. */
    private String builderVersion;
}
