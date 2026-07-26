package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Per-publication VENUE evidence. Named for DBLP because that was its only source; since H92 a row may also
 * carry Crossref-derived fields, and the unique index on {@code publicationId} means one row per
 * publication with each source filling in the parts it knows. {@code matchMethod} records provenance.
 * (The collection name is left alone deliberately — renaming a live collection is a migration with real
 * risk and no functional gain.)
 */
@Data
@Document(collection = "scholardex.publication_dblp_evidence")
@CompoundIndex(name = "uniq_scholardex_publication_dblp_publication", def = "{'publicationId': 1}", unique = true)
public class ScholardexPublicationDblpEvidence {
    @Id
    private String id;
    private String publicationId;
    private String dblpKey;
    private String dumpVersion;
    private String matchMethod;
    private String doi;
    private String title;
    private Integer year;
    private String booktitle;
    private String series;
    private String conferenceName;
    private String sourceUrl;
    private String ee;
    /**
     * H92 — the Springer VOLUME title from Crossref ({@code container-title[1]}), e.g. "Advanced Information
     * Networking and Applications" for a paper whose forum is only the series "Lecture Notes on Data
     * Engineering and Communications Technologies". A name to MATCH against CORE, never a venue identity:
     * {@code series} stays null on a Crossref-only row, so {@code rebuildFromEvidence} does not mint or
     * re-stamp a forum from it (that is how the "AINA Workshops" mint accident happened).
     */
    private String volumeTitle;
    /** When the Crossref lookup last ran — set even on a miss, so the sweep does not re-ask forever. */
    private Instant crossrefCheckedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
