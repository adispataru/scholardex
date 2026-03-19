package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "user_defined.forum_facts")
@CompoundIndex(name = "uniq_user_defined_forum_source_record_id", def = "{'sourceRecordId': 1}", unique = true)
public class UserDefinedForumFact implements HasLineageFields, HasReviewFields {
    @Id
    private String id;
    private String source;
    private String sourceRecordId;
    private String sourceEventId;
    private String sourceBatchId;
    private String sourceCorrelationId;

    private String publicationName;
    private String issn;
    private String eIssn;
    private String aggregationType;

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
