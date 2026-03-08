package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.affiliation_facts")
@CompoundIndex(name = "uniq_scholardex_affiliation_scopus_id", def = "{'scopusAffiliationIds': 1}", unique = true, sparse = true)
public class ScholardexAffiliationFact {
    @Id
    private String id;
    private List<String> scopusAffiliationIds = new ArrayList<>();
    private List<String> wosAffiliationIds = new ArrayList<>();
    private List<String> googleScholarAffiliationIds = new ArrayList<>();
    private List<String> userSourceAffiliationIds = new ArrayList<>();
    private String name;
    private String nameNormalized;
    private String city;
    private String country;
    private List<String> aliases = new ArrayList<>();
    private String sourceEventId;
    private String source;
    private String sourceRecordId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;
}
