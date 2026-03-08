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
@CompoundIndex(name = "uniq_scholardex_forum_scopus_id", def = "{'scopusForumIds': 1}", unique = true, sparse = true)
@CompoundIndex(name = "uniq_scholardex_forum_wos_id", def = "{'wosForumIds': 1}", unique = true, sparse = true)
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
    private List<String> aliasIssns = new ArrayList<>();
    private String aggregationType;
    private String aggregationTypeNormalized;

    private String sourceEventId;
    private String source;
    private String sourceRecordId;
    private String sourceBatchId;
    private String sourceCorrelationId;
    private Instant createdAt;
    private Instant updatedAt;
}
