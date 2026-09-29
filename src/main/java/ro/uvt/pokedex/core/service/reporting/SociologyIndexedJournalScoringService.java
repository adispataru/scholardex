package ro.uvt.pokedex.core.service.reporting;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Set;

/**
 * OM 3.019/2025, Comisia 25, indicator I.2 — journal articles outside I.1:
 * <ul>
 *   <li>a journal indexed in Scopus: 4 points, {@code category="SCOPUS"};</li>
 *   <li>a journal indexed in at least three recognised databases: 2 points, {@code category="BDI3"}.</li>
 * </ul>
 * The points are returned as {@code S} (the engine evaluates a formula only when S is positive), so the
 * indicator formula is {@code S * Coef_m / N}.
 *
 * <p><b>One indicator per publication</b> (definition [12]). The annex leaves the choice to the candidate; the
 * platform puts an article at I.1 whenever its journal has an impact factor, because criteria C.1–C.3 are
 * asked of I.1. Such an article returns 0 here with zeroReason {@code SCORED_BY_STRICTER}.</p>
 *
 * <p><b>What cannot be checked.</b> Of the recognised databases the platform knows Web of Science, Scopus,
 * DOAJ and ERIH Plus. A journal outside Scopus reaches three only through Web of Science + DOAJ + ERIH Plus;
 * one that reaches three through EBSCO, CEEOL, ProQuest and the like is not seen, and scores nothing here.</p>
 */
@Service
public class SociologyIndexedJournalScoringService extends AbstractWoSForumScoringService {

    static final double SCOPUS_POINTS = 4.0;
    static final double DATABASES_POINTS = 2.0;

    private static final Set<String> WOS_EDITIONS = Set.of("SCIE", "SSCI", "AHCI", "ESCI");

    public SociologyIndexedJournalScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.SOC_INDEXED_JOURNAL;
    }

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Score score = new Score();
        if (publication == null) {
            return score;
        }
        if (!isArticleOrReview(publication)) {
            score.getScoringInfo().put("zeroReason", "VENUE_TYPE_MISMATCH");
            return score;
        }
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());
        if (forum == null) {
            return score;
        }
        List<Integer> years = getAllowedYearsForPublication(publication, indicator);
        if (impactFactorOfPublicationYear(everyCategory(), forum, years).isPresent()) {
            score.getScoringInfo().put("zeroReason", "SCORED_BY_STRICTER");
            return score;
        }

        Set<String> databases = lookupPort.getForumIndexingDatabases(publication.getForumId());
        int year = years.isEmpty() ? 0 : years.getFirst();
        if (databases.contains("SCOPUS")) {
            return points(SCOPUS_POINTS, "SCOPUS", year);
        }
        long recognised = databases.stream().filter(Comisia25Rules.OTHER_RECOGNISED_DATABASES::contains).count()
                + (databases.stream().anyMatch(WOS_EDITIONS::contains) ? 1 : 0);
        if (recognised >= Comisia25Rules.DATABASES_REQUIRED) {
            return points(DATABASES_POINTS, "BDI3", year);
        }
        return score;
    }

    private Score points(double value, String category, int year) {
        Score score = new Score();
        score.setScore(value);
        score.setCoreRankingEquivalent(category); // reaches the formula as `category`
        score.setScoringSource(strategy().name());
        score.setYear(year);
        return score;
    }

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        return new Score(); // journal papers are publication-shaped, not activity-shaped
    }

    @Override
    public String getDescription() {
        return "Comisia 25 (2026) I.2: journal articles without an impact factor — Scopus journal S = 4 "
                + "(category SCOPUS), at least three recognised databases S = 2 (category BDI3); an article "
                + "whose journal has an impact factor is left to I.1.\n";
    }
}
