package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "user_defined.publication_facts")
@CompoundIndex(name = "uniq_user_defined_publication_source_record_id", def = "{'sourceRecordId': 1}", unique = true)
public class UserDefinedPublicationFact {
    @Id
    private String id;
    private String source;
    private String sourceRecordId;
    private String sourceEventId;
    private String sourceBatchId;
    private String sourceCorrelationId;

    private String forumSourceRecordId;
    private String eid;
    private String doi;
    private String title;
    private String subtype;
    private String subtypeDescription;
    private String creator;
    private Integer authorCount;
    private List<String> authorIds = new ArrayList<>();
    private List<String> authorAffiliationSourceIds = new ArrayList<>();
    private List<String> correspondingAuthors = new ArrayList<>();
    private List<String> affiliationIds = new ArrayList<>();
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
    private String reviewState;
    private String reviewReason;
    private Instant reviewStateUpdatedAt;
    private String reviewStateUpdatedBy;
    private String moderationFlow;
    private String wizardSubmitterEmail;
    private String wizardSubmitterResearcherId;
    private Instant wizardSubmittedAt;

    private String lastPayloadHash;
    private Instant lastMaterializedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
