package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H66B M7 — a book listed in the Scopus Books snapshot, keyed by its Scopus Source ID. Books are a
 * <b>distinct entity from forums</b>: a publication whose venue {@code aggregationType} is a book resolves to
 * a {@code bookId} here rather than minting a serial forum (M7-B). Carries the identity (ISBNs/title) +
 * classification (publisher/ASJC/year) book scoring needs (M7-C: {@code FeaaBookScoringService} reads the
 * publisher for Anexa-1 prestige-publisher membership).
 *
 * <p>Reference data streamed from the Scopus Books List xlsx (~475k rows) via the admin endpoint, NOT derived
 * from the source-replay ingest — owned but intentionally outside
 * {@code PipelineRebuildService.MANAGED_DERIVED_COLLECTIONS}, so it persists across a full rebuild
 * (re-import to refresh the snapshot), mirroring the DOAJ/ERIH reference loads.
 */
@Data
@Document(collection = "scholardex.book_facts")
public class ScholardexBookFact {

    /** Scopus Source ID — the stable identity for upsert and the {@code bookId} publications resolve against. */
    @Id
    private String id;

    private String scopusId;
    private String title;
    private String printIsbn;
    private String electronicIsbn;
    private String publisher;
    private Integer publicationYear;
    private List<String> asjc = new ArrayList<>();

    /** Snapshot stamp for the import (e.g. the Book List month/year). */
    private String asOf;
    private String source;
    private String sourceBatchId;
    private Instant createdAt;
    private Instant updatedAt;
}
