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
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;

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
        if (PredatoryVenueSupport.isExcludedVenue(forum)) {
            return createScore(initializeScoreResult()); // standard-excluded venue (WSEAS/IAENG/DAAAM): no points
        }

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);

        if (isJournalPublicationCandidate(publication, forum)) {
            // ItemYear indicators score in the paper's own year, but JCR rankings lag — carry the journal's last
            // known quartile forward so current-year papers still score.
            boolean carryForward = indicator.getEffectiveScoreYearRange() instanceof ScoreYearRangeSpec.ItemYear;
            boolean bestOfAisIf = indicator.usesJournalBestQuartile2026();
            computeScores(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    (ranking, year, category, rank) -> computeCSScore(ranking, year, category, rank, bestOfAisIf),
                    this::compareScoresByPoints,
                    carryForward
            );
            // No JIF quartile: a journal still earns C if it is in a recognized index. SCOPUS — the paper's own
            // Scopus eid OR the journal being Scopus-indexed (so OpenAlex-sourced papers with no eid still get C).
            // WoS AHCI and ESCI editions carry no JIF quartile either, but are recognized WoS indexes, so a journal
            // in any of them also floors at C and is reported by index (a journal in several shows e.g. SCOPUS+AHCI).
            if (scoreResult.bestPoints.get() == 0 && forum != null && forum.hasAggregationType("Journal")) {
                // WoS-edition membership is year-true (carry-forward): use the paper's own year, falling back to the
                // latest data year for the never-dated case so a current paper isn't dropped.
                int pubYear = PersistenceYearSupport
                        .extractYear(publication.getCoverDate(), publication.getId(), logger)
                        .orElseGet(lookupPort::maxAvailableYear);
                List<String> indexes = new java.util.ArrayList<>();
                if (publication.getEid() != null || lookupPort.isForumInScopus(forum.getId())) {
                    indexes.add("SCOPUS");
                }
                if (lookupPort.isForumInAhci(forum.getId(), pubYear)) {
                    indexes.add("AHCI");
                }
                if (lookupPort.isForumInEsci(forum.getId(), pubYear)) {
                    indexes.add("ESCI");
                }
                if (!indexes.isEmpty()) {
                    String primary = indexes.getFirst();
                    scoreResult.bestPoints.set(2.0);
                    scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
                    scoreResult.bestYear.set(lookupPort.maxAvailableYear());
                    // Sentinel quarter matches the primary index name (SCOPUS/AHCI/ESCI all exist as Quarter values).
                    scoreResult.bestQuarter.set(WoSRanking.Quarter.valueOf(primary));
                    scoreResult.scoringSource.set(String.join("+", indexes));
                    scoreResult.scoringInfo.put("matchSource", primary);
                    scoreResult.scoringInfo.put("fallbackReason", primary + "_FALLBACK");
                    scoreResult.scoringInfo.put("sourcesConsulted", List.copyOf(indexes));
                    if (indexes.contains("AHCI")) {
                        scoreResult.scoringInfo.put("ahci", true);
                    }
                    if (indexes.contains("ESCI")) {
                        scoreResult.scoringInfo.put("esci", true);
                    }
                }
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
        return forum != null && forum.hasAggregationType("Journal");
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
        boolean bestOfAisIf = indicator.usesJournalBestQuartile2026();
        computeScores(
                domain,
                forum,
                allowedYears,
                scoreResult,
                (ranking, year, category, rank) -> computeCSScore(ranking, year, category, rank, bestOfAisIf),
                this::compareScoresByPoints,
                carryForward
        );

        // Special case for SCOPUS-only journals
        if (scoreResult.bestPoints.get() == 0 &&
            forum != null &&
            forum.hasAggregationType("Journal")) {
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

    private Optional<Score> computeCSScore(
            WoSRanking ranking, int year, String category, WoSRanking.Rank rank, boolean bestOfAisIf) {
        // Only SCIE/SSCI placements count as quartile categories for Informatică. ESCI/AHCI journals carry
        // quartiles in the data (JCR ranks them since 2023, and the AIS files include ESCI cohorts), but the
        // standard treats those editions as "indexed, not core" — they fall through to the ESCI/AHCI
        // floor-at-C path in getScore instead of scoring Q1-Q4 points here.
        if (!isCoreQuartileEdition(category)) {
            return Optional.empty();
        }
        // 2016 standard: classification by AIS quartile alone. 2026 standard: the journal takes the BEST of
        // its AIS and JIF placements for the resolved year (each metric ranked against its own cohort). The
        // comparison is per-year, so years without JIF data (our JCR IF ends 2019 for now) fall back to AIS.
        WoSRanking.Quarter aisQuarter = rank.getQAis().get(year);
        Optional<Score> aisScore = aisQuarter == null
                ? Optional.empty()
                : scoreQuartilePlacement(year, category, aisQuarter, rank.getRankAis().get(year),
                        "AIS", lookupPort.getTopRankings(category, year));
        if (!bestOfAisIf) {
            return aisScore;
        }
        WoSRanking.Quarter ifQuarter = rank.getQIF().get(year);
        Optional<Score> ifScore = ifQuarter == null
                ? Optional.empty()
                : scoreQuartilePlacement(year, category, ifQuarter, rank.getRankIF().get(year),
                        "IF", lookupPort.getTopRankingsIf(category, year));
        if (aisScore.isEmpty()) {
            return ifScore;
        }
        if (ifScore.isEmpty()) {
            return aisScore;
        }
        // ties keep AIS (the primary metric) as the reported placement
        return ifScore.get().getScore() > aisScore.get().getScore() ? ifScore : aisScore;
    }

    /** The category index keys are "CATEGORY - EDITION"; quartile points only come from SCIE/SSCI. */
    private boolean isCoreQuartileEdition(String categoryIndexKey) {
        if (categoryIndexKey == null) {
            return false;
        }
        String key = categoryIndexKey.trim().toUpperCase(java.util.Locale.ROOT);
        return key.endsWith(" - SCIE") || key.endsWith(" - SSCI") || !key.contains(" - ");
    }

    private Optional<Score> scoreQuartilePlacement(
            int year, String category, WoSRanking.Quarter quarter, Integer metricRank, String metric, int topQ1Count) {
        if (quarter == null) {
            return Optional.empty();
        }

        Score score = new Score();
        int numTop = (int) (0.2 * topQ1Count);
        int rankPosition = metricRank == null ? Integer.MAX_VALUE : metricRank;

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
        scoringInfo.put("quartileMetric", metric);
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
                Scoring strategy for CNATDCU's Computer Science domain.(Category translation from WoS quarters;
                2026 indicators take the journal's BEST placement of AIS and JIF quartiles per year)
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
