package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

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
    private Instant createdAt;
    private Instant updatedAt;
}
