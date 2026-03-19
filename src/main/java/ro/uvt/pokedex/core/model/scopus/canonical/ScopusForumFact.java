package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.forum_facts")
@CompoundIndex(name = "uniq_scopus_forum_fact_source_id", def = "{'sourceId': 1}", unique = true)
public class ScopusForumFact implements HasLineageFields {
    @Id
    private String id;
    private String sourceId;
    private String publicationName;
    private String issn;
    private String eIssn;
    private String aggregationType;
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
