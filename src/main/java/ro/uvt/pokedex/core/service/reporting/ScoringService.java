package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

public interface ScoringService {
    Integer LAST_YEAR = 2023;
    Score getScore(Publication publication, Indicator indicator);
    Score getScore(ActivityInstance activity, Indicator indicator);
    String getDescription();
}
