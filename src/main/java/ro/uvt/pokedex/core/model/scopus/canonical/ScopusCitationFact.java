package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.citation_facts")
@CompoundIndex(name = "uniq_scopus_citation_fact_edge", def = "{'citedEid': 1, 'citingEid': 1}", unique = true)
public class ScopusCitationFact {
    @Id
    private String id;
    private String citedEid;
    private String citingEid;
    private String sourceEventId;
    private String source;
    private String sourceRecordId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private String lastPayloadHash;
    private Instant lastMaterializedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
