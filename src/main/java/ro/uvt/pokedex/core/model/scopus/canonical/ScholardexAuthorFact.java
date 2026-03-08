package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.author_facts")
@CompoundIndex(name = "uniq_scholardex_author_scopus_id", def = "{'scopusAuthorIds': 1}", unique = true, sparse = true)
public class ScholardexAuthorFact {
    @Id
    private String id;
    private List<String> scopusAuthorIds = new ArrayList<>();
    private List<String> wosAuthorIds = new ArrayList<>();
    private List<String> googleScholarAuthorIds = new ArrayList<>();
    private List<String> userSourceAuthorIds = new ArrayList<>();
    private String displayName;
    private String nameNormalized;
    private List<String> affiliationIds = new ArrayList<>();
    private List<String> pendingAffiliationSourceIds = new ArrayList<>();
    private String sourceEventId;
    private String source;
    private String sourceRecordId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;
}
