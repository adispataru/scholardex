package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scopus.publication_facts")
@CompoundIndex(name = "uniq_scopus_publication_fact_eid", def = "{'eid': 1}", unique = true)
public class ScopusPublicationFact {
    @Id
    private String id;
    private String doi;
    private String eid;
    private String title;
    private String subtype;
    private String subtypeDescription;
    private String scopusSubtype;
    private String scopusSubtypeDescription;
    private String creator;
    private Integer authorCount;
    private List<String> authors = new ArrayList<>();
    private List<String> correspondingAuthors = new ArrayList<>();
    private List<String> affiliations = new ArrayList<>();
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
