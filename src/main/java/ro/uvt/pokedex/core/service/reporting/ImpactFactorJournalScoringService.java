package ro.uvt.pokedex.core.service.reporting;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Optional;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;

/**
 * Scoring service that evaluates journals using the Impact Factor metric.
 * The implementation follows the pattern used in {@link AISJournalScoringService}.
 *
 * <p><b>COMISIA 28, 2026</b> ({@link Comisia28Rules}): the annex counts ESCI as Web of Science Core
 * Collection, so for its indicators a journal placed in a domain category through the ESCI edition is
 * scored like one in SCIE/SSCI, and a publication year without an impact factor takes the last one
 * available. Every other indicator keeps the SCIE/SSCI-only, exact-year behaviour.</p>
 */
@Service
public class ImpactFactorJournalScoringService extends AbstractWoSForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ImpactFactorJournalScoringService.class);
    private final Counter requestsCounter;
    private final Counter successCounter;
    private final Counter missingCounter;

    @Autowired
    public ImpactFactorJournalScoringService(ReportingLookupPort lookupPort, MeterRegistry meterRegistry) {
        super(lookupPort);
        this.requestsCounter = meterRegistry.counter("pokedex.reporting.if.requests");
        this.successCounter = meterRegistry.counter("pokedex.reporting.if.success");
        this.missingCounter = meterRegistry.counter("pokedex.reporting.if.missing");
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        requestsCounter.increment();
        boolean comisia28 = Comisia28Rules.of(indicator).isPresent();
        Domain domain = comisia28
                ? Comisia28Rules.withEmergingSources(indicator.getDomain())
                : indicator.getDomain();
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);
        int maxYear = lookupPort.maxAvailableYear();
        if(allowedYears.size() == 1 & allowedYears.getFirst() > maxYear){
            allowedYears.set(0, maxYear);
        }

        if (isArticleOrReview(publication)) {
            computeScores(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    // Impact Factor specific extractor
                    (ranking, year, category, rank) -> {
                        if( ranking.getScore() == null || ranking.getScore().getIF() == null || !ranking.getScore().getIF().containsKey(year)) {
                            return Optional.empty();
                        }
                        Score score = new Score();
                        score.setScore(ranking.getScore().getIF().get(year));
                        WoSRanking.Quarter qIF = rank.getQIF() != null ? rank.getQIF().get(year) : null;
                        // Before the unified ranking an ESCI quartile was computed against the edition's own
                        // small cohort — not the "above the median of the category" the annex means.
                        boolean cohortQuartile = ScoringCategorySupport.isEsciIndex(
                                ScoringCategorySupport.extractCategoryIndex(category))
                                && year < ScoringCategorySupport.ESCI_UNIFIED_FROM_YEAR;
                        score.setQuarter(qIF != null && !cohortQuartile ? qIF.toString() : null);
                        return Optional.of(score);
                    },
                    this::compareScoresByPoints,
                    comisia28 // "ultimul IF disponibil" when the publication year has none yet
            );
        }
        return finalizeWithTelemetry(
                createScore(scoreResult),
                "publication",
                publication == null ? null : publication.getId(),
                forum == null ? null : forum.getPublicationName()
        );
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        requestsCounter.increment();
        Domain domain = indicator.getDomain();
        ScholardexForumView forum = getForumFromActivity(activity);

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears =
                indicator.getEffectiveScoreYearRange().allowedYears(activity.getYear());

        computeScores(
                domain,
                forum,
                allowedYears,
                scoreResult,
                // Impact Factor specific extractor
                (ranking, year, category ,rank) -> {
                    if( ranking.getScore() == null || ranking.getScore().getIF() == null || !ranking.getScore().getIF().containsKey(year)) {
                        return Optional.empty();
                    }
                    Score score = new Score();
                    score.setScore(ranking.getScore().getIF().get(year));
                    score.setQuarter(rank.getQIF().get(year).toString());
                    return Optional.of(score);
                },
                this::compareScoresByPoints
        );
        return finalizeWithTelemetry(
                createScore(scoreResult),
                "activity",
                activity == null ? null : activity.getId(),
                forum == null ? null : forum.getPublicationName()
        );
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * An ESCI key counts only when the domain lists it <b>explicitly</b>. No stored domain does; the
     * COMISIA 28 path hands in a widened copy ({@link Comisia28Rules#withEmergingSources}). The catch-all
     * domain {@code ALL} is deliberately not enough, so nothing changes for any other indicator.
     */
    @Override
    protected boolean isCategoryInDomain(Domain domain, String category) {
        if (super.isCategoryInDomain(domain, category)) {
            return true;
        }
        return domain != null
                && domain.getWosCategories() != null
                && domain.getWosCategories().contains(category)
                && ScoringCategorySupport.isEsciIndex(ScoringCategorySupport.extractCategoryIndex(category));
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.IMPACT_FACTOR;
    }

    @Override
    public String getDescription() {
        return "Returns the impact factor\n";
    }

    private Score finalizeWithTelemetry(Score score, String context, String sourceId, String forumName) {
        if (score.getYear() > 0) {
            successCounter.increment();
            logger.info("IMPACT_FACTOR scoring resolved: strategy=IMPACT_FACTOR context={} sourceId={} year={} score={} quarter={} forum={}",
                    context, sourceId, score.getYear(), score.getScore(), score.getQuarter(), forumName);
        } else {
            missingCounter.increment();
            logger.info("IMPACT_FACTOR scoring missing: strategy=IMPACT_FACTOR context={} sourceId={} reason=if_missing_or_not_eligible forum={}",
                    context, sourceId, forumName);
        }
        return score;
    }
}
