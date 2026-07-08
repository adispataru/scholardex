package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scoring strategy for the PN-IV PD/TE minimal eligibility standard (Pachet de informații PD 2026,
 * Anexa 2, "Științele naturii, Științe exacte și Științele inginerești" — the family Informatică
 * belongs to). Deliberately NOT a CNATDCU scorer:
 *
 * <ul>
 *   <li>A work qualifies only when its journal was indexed in <b>SCIE, SSCI or AHCI</b> in the
 *       publication year (year-true with carry-forward). <b>ESCI membership does not qualify</b> —
 *       the standard names the three editions explicitly.</li>
 *   <li>The quartile is strictly the <b>AIS</b> placement within a WoS category of the indicator's
 *       domain ("cea mai favorabilă încadrare"), resolved from the publication year's JCR and capped
 *       at <b>JCR-2024</b> for 2025–2026 papers, with the usual carry-forward below the cap.</li>
 *   <li>No Scopus/ESCI/AHCI C-floors and no point translation: a qualifying work scores a flat
 *       1 venue point (so eligibility formulas count works), and {@code Q} carries the AIS quartile
 *       (or {@code NOT_FOUND} for members without an AIS placement, e.g. AHCI journals).</li>
 * </ul>
 *
 * <p>Candidates are WoS-journal-shaped works: articles/reviews, plus journal-published proceedings
 * papers (subtype {@code cp} in a Journal forum — the mentor standard accepts document type
 * "proceedings paper"; director formulas exclude it via the {@code docType} formula variable).
 * Books/chapters, Springer book-series DOIs, and anything on a Conference-Proceeding forum are
 * venue-type mismatches — the conference/book indicators own those.</p>
 */
@Service
public class PdWosEligibilityScoringService extends AbstractWoSForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(PdWosEligibilityScoringService.class);

    /**
     * PD 2026, Anexa 2 note: "Pentru articolele publicate în perioada 2025-2026, se va lua în
     * considerare clasificarea revistelor indexate Web of Science, în funcție de valoarea AIS,
     * conform JCR-2024 (ediția iunie 2025)." Papers from 2024 and earlier use their own year's JCR.
     */
    static final int LAST_REFERENCE_JCR_YEAR = 2024;

    @Autowired
    public PdWosEligibilityScoringService(ReportingLookupPort lookupPort) {
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
        boolean candidate = isPdJournalCandidate(publication, forum);
        if (candidate && forum != null) {
            int pubYear = PersistenceYearSupport
                    .extractYear(publication.getCoverDate(), publication.getId(), logger)
                    .orElseGet(lookupPort::maxAvailableYear);

            List<String> editions = qualifyingEditions(forum.getId(), pubYear);
            if (!editions.isEmpty()) {
                // The AIS placement resolves from the publication year's JCR, capped at JCR-2024 for
                // 2025-2026 papers; carry-forward below the cap keeps recent papers in lagging journals scored.
                int rankingYear = Math.min(pubYear, LAST_REFERENCE_JCR_YEAR);
                computeScores(
                        domain,
                        forum,
                        List.of(rankingYear),
                        scoreResult,
                        this::computeAisQuartilePlacement,
                        this::compareScoresByPoints,
                        true
                );
                // Membership alone qualifies the work (1 point) even without an AIS placement —
                // AHCI journals carry no AIS, and the "≥ N works" criteria count them.
                scoreResult.bestPoints.set(1.0);
                if (scoreResult.bestYear.get() == 0) {
                    scoreResult.bestYear.set(pubYear);
                }
                scoreResult.scoringSource.set(String.join("+", editions));
                scoreResult.scoringInfo.put("wosEditions", List.copyOf(editions));
                scoreResult.scoringInfo.putIfAbsent("matchSource", "WOS");
                scoreResult.scoringInfo.putIfAbsent("sourcesConsulted", List.of("WOS"));
            }
        }

        Score score = createScore(scoreResult);
        if (!candidate) {
            // Not a WoS-journal-shaped work (book/chapter, conference-proceeding venue, …): the
            // matching indicator owns it. Candidate zeros (a real article whose journal is simply
            // not SCIE/SSCI/AHCI) deliberately stay unstamped.
            score.getScoringInfo().put("zeroReason", "VENUE_TYPE_MISMATCH");
        }
        return score;
    }

    private boolean isPdJournalCandidate(ScoringPublicationReadModel publication, ScholardexForumView forum) {
        // Books and chapters are never journal works, whatever the forum label says.
        if (PublicationSubtypeSupport.isSubtype(publication, "ch", "bk")) {
            return false;
        }
        // Springer ISBN DOI (10.1007/978…): a book-series/LNCS proceedings volume even when the
        // subtype says "article".
        if (DoiVenueSupport.isSpringerBookSeriesProceedings(publication)) {
            return false;
        }
        // Conference venues are out: CPCI conferences don't qualify under the PD standard ("reviste
        // indexate"), and OpenAlex labels conference papers "article" — the forum is authoritative.
        if (forum != null && forum.hasAggregationType("Conference Proceeding")) {
            return false;
        }
        if (PublicationSubtypeSupport.isSubtype(publication, "cp")) {
            // A proceedings paper published in a journal (WoS document type "proceedings paper" /
            // special issue): a candidate — the mentor standard accepts it, director formulas
            // exclude it via docType.
            return forum != null && forum.hasAggregationType("Journal");
        }
        return isArticleOrReview(publication);
    }

    /** SCIE/SSCI/AHCI memberships in the publication year (year-true, carry-forward). ESCI never qualifies. */
    private List<String> qualifyingEditions(String forumId, int pubYear) {
        List<String> editions = new ArrayList<>();
        if (lookupPort.isForumInScie(forumId, pubYear)) {
            editions.add("SCIE");
        }
        if (lookupPort.isForumInSsci(forumId, pubYear)) {
            editions.add("SSCI");
        }
        if (lookupPort.isForumInAhci(forumId, pubYear)) {
            editions.add("AHCI");
        }
        return editions;
    }

    /**
     * AIS quartile placement for one (category, year). Only SCIE/SSCI category placements count —
     * the domain gate already restricts to " - SCIE"/" - SSCI" keys, but the guard keeps an ALL-domain
     * indicator from picking up ESCI placements (present in the data since the 2023 unified ranking).
     */
    private Optional<Score> computeAisQuartilePlacement(
            WoSRanking ranking, int year, String category, WoSRanking.Rank rank) {
        if (!ScoringCategorySupport.isScieOrSsciIndex(ScoringCategorySupport.extractCategoryIndex(category))) {
            return Optional.empty();
        }
        WoSRanking.Quarter aisQuarter = rank.getQAis().get(year);
        if (aisQuarter == null) {
            return Optional.empty();
        }
        Score score = new Score();
        // Flat 1 point: eligibility formulas count works and gate on Q; there is no point scale.
        score.setScore(1.0);
        score.setQuarter(aisQuarter.toString());
        Map<String, Object> scoringInfo = new LinkedHashMap<>();
        scoringInfo.put("matchSource", "WOS");
        scoringInfo.put("resolvedYear", year);
        scoringInfo.put("quarter", aisQuarter.toString());
        scoringInfo.put("quartileMetric", "AIS");
        scoringInfo.put("wosCategory", category);
        scoringInfo.put("sourcesConsulted", List.of("WOS"));
        setProvenance(score, "WOS", scoringInfo);
        return Optional.of(score);
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        // The PD eligibility standard counts publications only; activities never score here.
        return createScore(initializeScoreResult());
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.PD_WOS;
    }

    @Override
    public String getDescription() {
        return """
                PN-IV PD/TE minimal-eligibility strategy (Pachet de informații PD 2026, Anexa 2).
                A work qualifies when its journal was indexed in SCIE, SSCI or AHCI in the publication
                year (ESCI does not qualify). Each qualifying work scores 1 point; Q carries the AIS
                quartile within the indicator's domain (publication-year JCR, capped at JCR-2024 for
                2025-2026 papers). Eligibility formulas count works and gate on Q/docType.
                """;
    }
}
