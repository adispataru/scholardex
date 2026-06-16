package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * H66 A3: WoS edition-coverage fact — "journal is indexed in edition E (year/as-of) for category C", with no
 * metric/quartile. Distinct from {@link WosCategoryFact} (which is metric-keyed: {@code metricType NOT NULL})
 * so MJL coverage cannot ride it without polluting the scoring path. Sourced from the MJL coverage export;
 * projected (B2) into the forum membership/category view by {@code wosForumIds}, alongside year-true JCR
 * category facts.
 */
@Data
@Document(collection = "wos.coverage_facts")
@CompoundIndex(name = "uniq_coverage_fact", def = "{'journalId': 1, 'year': 1, 'editionNormalized': 1, 'categoryNameCanonical': 1}", unique = true)
public class WosCoverageFact {
    @Id
    private String id;
    private String journalId;
    private Integer year;
    private EditionNormalized editionNormalized;
    /** Nullable: edition coverage may carry no WoS category. */
    private String categoryNameCanonical;
    private WosSourceType sourceType;
    private String sourceEventId;
    private String sourceFile;
    private String sourceVersion;
    private String sourceRowItem;
    private Instant createdAt;
    /** Builder-logic version that produced this fact. */
    private String builderVersion;
}
