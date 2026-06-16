package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    private String isbn;
    private String aggregationType;
    private String publisher;
    /** H66 A2: normalized venue kind (journal/book-series/conference/trade), from the CiteScore Type column. */
    private String forumType;
    /** H66 A2: Scopus ASJC sub-subject codes (unioned across the source's CiteScore rows). */
    private List<String> asjc = new ArrayList<>();
    private String sourceEventId;
    private String source;
    private String sourceRecordId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private String lastPayloadHash;
    private Instant lastMaterializedAt;
    private Instant createdAt;
    private Instant updatedAt;
    /** Builder-logic version that produced this fact (H54.6b). */
    private String builderVersion;
}
