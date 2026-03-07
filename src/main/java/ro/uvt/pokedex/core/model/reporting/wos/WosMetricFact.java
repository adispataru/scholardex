package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "wos.metric_facts")
@CompoundIndex(name = "uniq_metric_fact", def = "{'journalId': 1, 'year': 1, 'metricType': 1}", unique = true)
public class WosMetricFact {
    @Id
    private String id;
    private String journalId;
    private Integer year;
    private MetricType metricType;
    private Double value;
    private WosSourceType sourceType;
    private String sourceEventId;
    private String sourceFile;
    private String sourceVersion;
    private String sourceRowItem;
    private Instant createdAt;
}
