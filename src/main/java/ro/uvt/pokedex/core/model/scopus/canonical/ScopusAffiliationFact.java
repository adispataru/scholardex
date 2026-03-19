package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.affiliation_facts")
@CompoundIndex(name = "uniq_scopus_affiliation_fact_afid", def = "{'afid': 1}", unique = true)
public class ScopusAffiliationFact implements HasLineageFields {
    @Id
    private String id;
    private String afid;
    private String name;
    private String city;
    private String country;
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
