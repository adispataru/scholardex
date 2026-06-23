package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;

/**
 * Scoring service that evaluates Computer Science journals based on WoS quartiles.
 */
@Service
public class ComputerScienceJournalScoringService extends AbstractWoSForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ComputerScienceJournalScoringService.class);

    @Autowired
    public ComputerScienceJournalScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Domain domain = indicator.getDomain();
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);

        if (isJournalPublicationCandidate(publication, forum)) {
            // ItemYear indicators score in the paper's own year, but JCR rankings lag — carry the journal's last
            // known quartile forward so current-year papers still score.
            boolean carryForward = indicator.getEffectiveScoreYearRange() instanceof ScoreYearRangeSpec.ItemYear;
            computeScores(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    this::computeCSScore,
                    this::compareScoresByPoints,
                    carryForward
            );
            // Special case for SCOPUS-only journals: the paper's own Scopus eid, OR the journal being Scopus-indexed
            // (forum membership) — the latter so OpenAlex-sourced papers (no eid) in Scopus journals still get C.
            if (scoreResult.bestPoints.get() == 0 &&
                    forum != null &&
                    "Journal".equals(forum.getAggregationType()) &&
                    (publication.getEid() != null || lookupPort.isForumInScopus(forum.getId()))) {
                scoreResult.bestPoints.set(2.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
                scoreResult.bestYear.set(lookupPort.maxAvailableYear());
                scoreResult.scoringSource.set("SCOPUS");
                scoreResult.scoringInfo.put("matchSource", "SCOPUS");
                scoreResult.scoringInfo.put("fallbackReason", "SCOPUS_FALLBACK");
                scoreResult.scoringInfo.put("sourcesConsulted", List.of("SCOPUS"));
            }
        }

        if (scoreResult.bestPoints.get() > 0 && "WOS".equals(scoreResult.scoringSource.get())) {
            scoreResult.scoringSource.set("SCOPUS+WOS");
            scoreResult.scoringInfo.put("matchSource", "WOS");
            scoreResult.scoringInfo.put("sourcesConsulted", List.of("SCOPUS", "WOS"));
        }

        return createScore(scoreResult);
    }

    private boolean isJournalPublicationCandidate(ScoringPublicationReadModel publication, ScholardexForumView forum) {
        // Book chapters (ch) and books (bk) are never journal articles, even when the forum's (often
        // unreliable) aggregationType says "Journal" — OpenAlex routinely mislabels book series and
        // LNCS/LNAI venues as "Journal". The subtype is authoritative; defer these to the book scorer.
        // (Conference papers "cp" are intentionally NOT excluded: proceedings published in a WoS-indexed
        // journal special issue legitimately score as journals.)
        if (PublicationSubtypeSupport.isSubtype(publication, "ch", "bk")) {
            return false;
        }
        // DOI-prefix backstop (clas.c springer_ch): a Springer ISBN DOI (10.1007/978…) is a book /
        // chapter / LNCS proceedings volume even when OpenAlex mislabels the subtype as "article" — catches
        // the cases the subtype check misses.
        if (DoiVenueSupport.isSpringerBookSeriesProceedings(publication)) {
            return false;
        }
        if (isArticleOrReview(publication)) {
            return true;
        }
        return forum != null && "Journal".equals(forum.getAggregationType());
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        Domain domain = indicator.getDomain();
        ScholardexForumView forum = getForumFromActivity(activity);

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = 
                indicator.getEffectiveScoreYearRange().allowedYears(activity.getYear());

        boolean carryForward = indicator.getEffectiveScoreYearRange() instanceof ScoreYearRangeSpec.ItemYear;
        computeScores(
                domain,
                forum,
                allowedYears,
                scoreResult,
                this::computeCSScore,
                this::compareScoresByPoints,
                carryForward
        );

        // Special case for SCOPUS-only journals
        if (scoreResult.bestPoints.get() == 0 &&
            forum != null &&
            "Journal".equals(forum.getAggregationType())) {
            scoreResult.bestPoints.set(2.0);
            scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
            scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
            scoreResult.bestYear.set(lookupPort.maxAvailableYear());
            scoreResult.scoringSource.set("SCOPUS");
            scoreResult.scoringInfo.put("matchSource", "SCOPUS");
            scoreResult.scoringInfo.put("fallbackReason", "SCOPUS_FALLBACK");
            scoreResult.scoringInfo.put("sourcesConsulted", List.of("SCOPUS"));
        }

        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  CS-specific scoring logic                                        */
    /* ------------------------------------------------------------------ */

    private Optional<Score> computeCSScore(WoSRanking ranking, int year, String category, WoSRanking.Rank rank) {
        WoSRanking.Quarter quarter = rank.getQAis().get(year);
        if (quarter == null) {
            return Optional.empty();
        }

        Score score = new Score();

        // TODO Come up with a better caching mechanism
        int top = lookupPort.getTopRankings(category, year);
        int numTop = (int) (0.2 * top);
        int rankPosition = rank.getRankAis().getOrDefault(year, Integer.MAX_VALUE);

        double points;
        switch (quarter) {
            case Q1 -> points = (rankPosition < numTop) ? 12.0 : 8.0;
            case Q2 -> points = (rankPosition < numTop) ? 8.0 : 4.0;
            case Q3 -> points = (rankPosition < numTop) ? 4.0 : 2.0;
            case Q4 -> points = 2.0;
            default -> points = 0.0;
        }

        score.setScore(points);
        score.setQuarter(quarter.toString());
        score.setCoreRankingEquivalent(getCategory(points).toString());
        Map<String, Object> scoringInfo = new LinkedHashMap<>();
        scoringInfo.put("matchSource", "WOS");
        scoringInfo.put("resolvedYear", year);
        scoringInfo.put("resolvedRank", score.getCoreRankingEquivalent());
        scoringInfo.put("quarter", quarter.toString());
        scoringInfo.put("wosCategory", category);
        scoringInfo.put("sourcesConsulted", List.of("WOS"));
        setProvenance(score, "WOS", scoringInfo);
        return Optional.of(score);
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.CS_JOURNAL;
    }

    @Override
    public String getDescription() {
        return """
                Scoring strategy for CNATDCU's Computer Science domain.(Category translation from WoS quarters)
                x = 20% * num(Q1) in the same WoS category
                A* = 12p (first x in Q1)
                A = 8p (rest of Q1 + first x in Q2)
                B = 4p (rest of Q2 + first x in Q3)
                C = 2p (rest of Q3 and Q4)
                C = 2p (non WoS, but indexed by SCOPUS)
                D = 1p (non WoS, non SCOPUS)
                """;
    }
}
