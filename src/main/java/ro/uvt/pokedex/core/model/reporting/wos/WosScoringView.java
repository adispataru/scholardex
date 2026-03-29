package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;

import java.time.Instant;

@Data
public class WosScoringView {
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
