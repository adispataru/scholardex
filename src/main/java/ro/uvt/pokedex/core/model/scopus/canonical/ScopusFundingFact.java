package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.funding_facts")
@CompoundIndex(name = "uniq_scopus_funding_fact_key", def = "{'fundingKey': 1}", unique = true)
public class ScopusFundingFact {
    @Id
    private String id;
    private String acronym;
    private String number;
    private String sponsor;
    private String fundingKey;
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
