package ro.uvt.pokedex.core.model.reporting.wos;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class WosRankingView {
    private String id;
    private String name;
    private String issn;
    private String eIssn;
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
