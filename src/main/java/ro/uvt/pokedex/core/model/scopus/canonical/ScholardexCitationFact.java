package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scholardex.citation_facts")
@CompoundIndex(
        name = "uniq_scholardex_citation_edge",
        def = "{'citedPublicationId': 1, 'citingPublicationId': 1, 'source': 1}",
        unique = true
)
public class ScholardexCitationFact {
    @Id
    private String id;
    private String citedPublicationId;
    private String citingPublicationId;
    private String source;
    private String sourceRecordId;
    private String sourceEventId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;
}
