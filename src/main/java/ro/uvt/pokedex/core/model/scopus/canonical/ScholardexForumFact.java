package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.forum_facts")
// partialFilter (not sparse): a multikey unique index over an array field indexes empty
// arrays under a single "undefined" key, so every forum lacking scopus ids would collide.
// sparse does not skip empty arrays; {$type:'string'} excludes empty/absent arrays and
// enforces uniqueness only across docs that actually carry at least one id. ($ne/$size are
// not permitted in partialFilterExpression.) See H54.2 / docs/data-ownership-inventory.md.
@CompoundIndex(name = "uniq_scholardex_forum_scopus_id", def = "{'scopusForumIds': 1}", unique = true, partialFilter = "{'scopusForumIds': {'$type': 'string'}}")
@CompoundIndex(name = "uniq_scholardex_forum_wos_id", def = "{'wosForumIds': 1}", unique = true, partialFilter = "{'wosForumIds': {'$type': 'string'}}")
public class ScholardexForumFact {
    @Id
    private String id;

    private List<String> scopusForumIds = new ArrayList<>();
    private List<String> wosForumIds = new ArrayList<>();
    private List<String> googleScholarForumIds = new ArrayList<>();
    private List<String> userSourceForumIds = new ArrayList<>();

    private String name;
    private String nameNormalized;
    private String issn;
    private String eIssn;
    private String isbn;
    private List<String> aliasIssns = new ArrayList<>();
    private String aggregationType;
    private String aggregationTypeNormalized;
    private String publisher;
    private String reviewState;
    private String reviewReason;
    private Instant reviewStateUpdatedAt;
    private String reviewStateUpdatedBy;
    private String moderationFlow;
    private String wizardSubmitterEmail;
    private String wizardSubmitterResearcherId;
    private Instant wizardSubmittedAt;

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
