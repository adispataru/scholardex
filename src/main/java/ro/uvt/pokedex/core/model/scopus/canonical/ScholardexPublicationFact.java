package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.publication_facts")
@CompoundIndex(name = "uniq_scholardex_publication_fact_eid", def = "{'eid': 1}", unique = true, sparse = true)
@CompoundIndex(name = "uniq_scholardex_publication_fact_wos_id", def = "{'wosId': 1}", unique = true, sparse = true)
@CompoundIndex(name = "uniq_scholardex_publication_fact_google_scholar_id", def = "{'googleScholarId': 1}", unique = true, sparse = true)
@CompoundIndex(name = "uniq_scholardex_publication_fact_user_source_id", def = "{'userSourceId': 1}", unique = true, sparse = true)
public class ScholardexPublicationFact {
    @Id
    private String id;
    private String doi;
    private String doiNormalized;
    private String title;
    private String titleNormalized;

    private String eid;
    private String wosId;
    private String googleScholarId;
    private String userSourceId;

    private String subtype;
    private String subtypeDescription;
    private String scopusSubtype;
    private String scopusSubtypeDescription;
    private String creator;
    private Integer authorCount;
    private List<String> authorIds = new ArrayList<>();
    private List<String> correspondingAuthors = new ArrayList<>();
    private List<String> affiliationIds = new ArrayList<>();
    private String forumId;
    private String volume;
    private String issueIdentifier;
    private String coverDate;
    private String coverDisplayDate;
    private String description;
    private Integer citedByCount;
    private Boolean openAccess;
    private String freetoread;
    private String freetoreadLabel;
    private String fundingId;
    private String articleNumber;
    private String pageRange;
    private Boolean approved;

    private String sourceEventId;
    private String source;
    private String sourceRecordId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;
}
