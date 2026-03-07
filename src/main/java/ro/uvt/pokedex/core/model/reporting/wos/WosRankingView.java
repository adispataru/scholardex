package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "wos.ranking_view")
public class WosRankingView {
    @Id
    private String id;
    @Indexed
    private String name;
    @Indexed
    private String issn;
    @Indexed
    private String eIssn;
    @Indexed
    private List<String> alternativeIssns = new ArrayList<>();
    private List<String> alternativeNames = new ArrayList<>();
    private String nameNorm;
    private String issnNorm;
    private String eIssnNorm;
    private List<String> alternativeIssnsNorm = new ArrayList<>();
    private Integer latestAisYear;
    private Integer latestRisYear;
    private EditionNormalized latestEditionNormalized;
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
}
