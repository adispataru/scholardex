package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "wos.category_facts")
@CompoundIndex(name = "uniq_category_fact", def = "{'journalId': 1, 'year': 1, 'categoryNameCanonical': 1, 'editionNormalized': 1, 'metricType': 1}", unique = true)
public class WosCategoryFact {
    @Id
    private String id;
    private String journalId;
    private Integer year;
    private String categoryNameCanonical;
    private String editionRaw;
    private EditionNormalized editionNormalized;
    private MetricType metricType;
    private String quarter;
    private Integer quartileRank;
    private Integer rank;
    private WosSourceType sourceType;
    private String sourceEventId;
    private String sourceFile;
    private String sourceVersion;
    private String sourceRowItem;
    private Instant createdAt;
}
