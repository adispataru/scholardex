package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "wos.scoring_view")
public class WosScoringView {
    @Id
    private String id;
    private String journalId;
    private Integer year;
    private String categoryNameCanonical;
    private EditionNormalized editionNormalized;
    private MetricType metricType;
    private Double value;
    private String quarter;
    private Integer quartileRank;
    private Integer rank;
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
}
