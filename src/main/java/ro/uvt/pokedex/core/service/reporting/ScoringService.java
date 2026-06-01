package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

public interface ScoringService {

    /**
     * H52 slice 6: the {@link ScoringStrategy} this service implements. Used by
     * {@link ScoringFactoryService} to build its {@code Map} registry — replaces the
     * pre-v1 hand-written if/else ladder. Every concrete implementation declares
     * exactly one strategy; adding a new strategy is "create the service, add the
     * enum value, done." The factory throws at startup if two beans claim the same
     * strategy or any production strategy is unclaimed.
     */
    ScoringStrategy strategy();

    Score getScore(ScoringPublicationReadModel publication, Indicator indicator);
    Score getScore(ActivityInstance activity, Indicator indicator);
    String getDescription();
}
