package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scholardex.publication_author_affiliation_facts")
@CompoundIndex(
        name = "uniq_scholardex_publication_author_affiliation_edge",
        def = "{'publicationId': 1, 'authorId': 1, 'affiliationId': 1, 'source': 1}",
        unique = true
)
public class ScholardexPublicationAuthorAffiliationFact {
    @Id
    private String id;
    private String publicationId;
    private String authorId;
    private String affiliationId;
    private String source;
    private String sourceRecordId;
    private String sourceEventId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private String linkState;
    private String linkReason;
    private Instant createdAt;
    private Instant updatedAt;
}
