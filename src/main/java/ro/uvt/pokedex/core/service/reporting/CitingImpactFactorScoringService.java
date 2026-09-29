package ro.uvt.pokedex.core.service.reporting;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.Optional;

/**
 * OM 3.019/2025, Comisia 25, indicator I.9: a citation earns {@code (0,2 + 4·f) · 2 / n}, where f is the
 * impact factor of the journal the CITING work appeared in and n the number of authors of the cited one.
 *
 * <p>The annex counts citations in Web of Science journals, in book chapters and volumes, and in journals
 * indexed in international databases — in practice every scholarly source that cites. So every citing work
 * scores: f is 0 when the source has no impact factor, and the citation is worth the floor, 0,2.</p>
 *
 * <p>The scorer returns {@code 0,2 + 4·f} as S, never 0, because the engine evaluates the indicator formula
 * only when S is positive. The formula is {@code S * 2 / N}; the count asked by criterion C.8 is the same
 * indicator with the formula {@code 1}.</p>
 *
 * <p>f follows definition [9]: the impact factor of the year the citing work was published, the latest one
 * available when that year is newer than the published data, in any category and any edition.</p>
 */
@Service
public class CitingImpactFactorScoringService extends AbstractWoSForumScoringService {

    static final double FLOOR = 0.2;
    static final double PER_IMPACT_FACTOR = 4.0;

    public CitingImpactFactorScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.CITING_IMPACT_FACTOR;
    }

    @Override
    public Score getScore(ScoringPublicationReadModel citing, Indicator indicator) {
        Score score = new Score();
        if (citing == null) {
            return score;
        }
        Optional<Score> impactFactor = Optional.empty();
        if (isArticleOrReview(citing) && citing.getForumId() != null && !citing.getForumId().isBlank()) {
            ScholardexForumView forum = lookupPort.getForum(citing.getForumId());
            impactFactor = impactFactorOfPublicationYear(
                    everyCategory(), forum, getAllowedYearsForPublication(citing, indicator));
        }
        double f = impactFactor.map(Score::getScore).orElse(0.0);
        score.setScore(FLOOR + PER_IMPACT_FACTOR * f);
        score.setYear(impactFactor.map(Score::getYear).orElse(0));
        score.setCoreRankingEquivalent(impactFactor.isPresent() ? "IF" : "NO_IF"); // the formula's `category`
        score.setScoringSource(strategy().name());
        score.getScoringInfo().put("citingImpactFactor", f);
        return score;
    }

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        return new Score(); // citations are publication-shaped
    }

    @Override
    public String getDescription() {
        return "Comisia 25 (2026) I.9: S = 0,2 + 4·f, f = impact factor of the citing journal in the year of "
                + "the citing work (0 when it has none); formula S * 2 / N.\n";
    }
}
