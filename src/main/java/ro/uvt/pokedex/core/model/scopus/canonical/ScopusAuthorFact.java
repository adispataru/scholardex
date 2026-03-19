package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scopus.author_facts")
@CompoundIndex(name = "uniq_scopus_author_fact_author_id", def = "{'authorId': 1}", unique = true)
public class ScopusAuthorFact implements HasLineageFields {
    @Id
    private String id;
    private String authorId;
    private String name;
    private List<String> affiliationIds = new ArrayList<>();
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
