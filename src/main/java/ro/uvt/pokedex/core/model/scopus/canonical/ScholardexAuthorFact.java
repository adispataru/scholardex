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
// partialFilter (not sparse): exclude empty/absent scopusAuthorIds arrays from the multikey
// unique index; sparse does not skip empty arrays. See H54.2.
@CompoundIndex(name = "uniq_scholardex_author_scopus_id", def = "{'scopusAuthorIds': 1}", unique = true, partialFilter = "{'scopusAuthorIds': {'$type': 'string'}}")
public class ScholardexAuthorFact {
    @Id
    private String id;
    private List<String> scopusAuthorIds = new ArrayList<>();
    private List<String> wosAuthorIds = new ArrayList<>();
    private List<String> googleScholarAuthorIds = new ArrayList<>();
    private List<String> userSourceAuthorIds = new ArrayList<>();
    private String displayName;
    private List<String> alternativeNames = new ArrayList<>();
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
    /** Builder-logic version that produced this fact (H54.6b). */
    private String builderVersion;
}
