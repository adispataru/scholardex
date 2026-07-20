package ro.uvt.pokedex.core.service.reporting;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FSP (Psihologie, Anexa 28) I2/I6 fallback scorer — the "less strict" rung under I1/I5. The fișă
 * files each publication under a single, most favorable indicator, so this scorer first re-checks the
 * strict I1 gate (psychology-category WoS journal with IF ≥ p, or above-median Q1/Q2 — p = 1.00 for
 * Psihologie, mirroring the Psiho_I1 formula) and returns 0 with zeroReason {@code SCORED_BY_STRICTER}
 * for anything I1 already counts. For the rest, in order:
 * <ol>
 *   <li>WoS-ranked in ANY category ("domenii de graniţă" journals like JMIR Formative Research):
 *       S = that IF (year carry-forward), {@code category="WOS"};</li>
 *   <li>membership in ≥2 recognized non-WoS BDIs (SCOPUS / DOAJ / ERIH): S = 0, {@code category="BDI2"}
 *       — the fișă's "indexate în cel puţin două baze de date internaţionale recunoscute";</li>
 *   <li>exactly one BDI: {@code category="BDI1"}, S = 0 (scores nothing under I2's formula; exposed for
 *       the single-BDI indicators).</li>
 * </ol>
 * Unindexed venues get no score. S carries the fișă's full points (3 + IF; 3 for BDI2) because the
 * engine skips formula evaluation when S is 0 — indicator formulas are simply {@code S} (I2, main
 * author) and {@code S/N} (I6, co-author).
 */
@Service
public class PsychBdiJournalScoringService extends AbstractWoSForumScoringService {

    /** The fișă's IF relevance threshold p for Psihologie. Mirrors the Psiho_I1 formula's S>=1 gate. */
    private static final double IF_THRESHOLD_P = 1.0;

    /** The fișă's I2 base: "3 + IF" per article. */
    private static final double BASE_POINTS = 3.0;

    /** Fișă-recognized BDIs we have membership data for, WoS editions excluded ("altele decât WoS"). */
    private static final Set<String> RECOGNIZED_BDI = Set.of("SCOPUS", "DOAJ", "ERIH");

    public PsychBdiJournalScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.PSYCH_BDI_JOURNAL;
    }

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Score score = new Score();
        if (publication == null || !isArticleOrReview(publication)) {
            return score;
        }
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());
        if (forum == null) {
            return score;
        }
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);
        int maxYear = lookupPort.maxAvailableYear();
        if (allowedYears.size() == 1 && allowedYears.getFirst() > maxYear) {
            allowedYears.set(0, maxYear);
        }

        // 1. Strict-path re-check: anything Psiho_I1/I5 counts must not double-count here.
        Score strict = bestIfScore(indicator.getDomain(), forum, allowedYears);
        if (strict != null && qualifiesForStrictPath(strict)) {
            score.getScoringInfo().put("zeroReason", "SCORED_BY_STRICTER");
            return score;
        }

        // The engine evaluates the indicator formula only when the base score S is positive, so this
        // scorer returns the fișă's FULL points (3 + IF) as S — the formulas are just S (main) and S/N
        // (co-author), mirroring the FEAA coefficient-as-S pattern.

        // 2. WoS-ranked in any category (borderline-domain journal) → 3 + IF.
        Domain anyDomain = new Domain();
        anyDomain.setName("ALL");
        Score anyWos = bestIfScore(anyDomain, forum, allowedYears);
        if (anyWos != null) {
            score.setScore(BASE_POINTS + anyWos.getScore());
            score.setQuarter(anyWos.getQuarter());
            score.setYear(anyWos.getYear());
            score.setCoreRankingEquivalent("WOS");
            score.setScoringSource(strategy().name());
            return score;
        }

        // 3. Recognized-BDI membership count (non-WoS): >=2 → 3 + IF·0 = 3 points.
        long bdiCount = lookupPort.getForumIndexingDatabases(publication.getForumId()).stream()
                .filter(RECOGNIZED_BDI::contains)
                .count();
        if (bdiCount >= 2) {
            score.setScore(BASE_POINTS);
            score.setCoreRankingEquivalent("BDI2");
            score.setScoringSource(strategy().name());
            score.setYear(allowedYears.isEmpty() ? 0 : allowedYears.getFirst());
        } else if (bdiCount == 1) {
            // Not punctable for I2 (needs >=2 BDIs) — exposed for transparency, scores 0.
            score.setScore(0.0);
            score.setCoreRankingEquivalent("BDI1");
            score.setScoringSource(strategy().name());
            score.setYear(allowedYears.isEmpty() ? 0 : allowedYears.getFirst());
        }
        return score;
    }

    /** True when the resolved in-domain IF score passes the Psiho_I1 gate (IF>=p or above-median Q1/Q2). */
    private static boolean qualifiesForStrictPath(Score strict) {
        if (strict.getScore() >= IF_THRESHOLD_P) {
            return true;
        }
        String quarter = strict.getQuarter();
        return "Q1".equals(quarter) || "Q2".equals(quarter);
    }

    /**
     * Best IF-based score for the forum over the allowed years within {@code domain} (name "ALL" = any
     * category), with the shared year carry-forward so current-year papers use the latest JCR. Returns
     * {@code null} when no ranked IF resolves at all.
     */
    private Score bestIfScore(Domain domain, ScholardexForumView forum, List<Integer> allowedYears) {
        ScoreResult result = initializeScoreResult();
        computeScores(
                domain,
                forum,
                allowedYears,
                result,
                (ranking, year, category, rank) -> {
                    if (ranking.getScore() == null || ranking.getScore().getIF() == null
                            || !ranking.getScore().getIF().containsKey(year)) {
                        return Optional.empty();
                    }
                    Score s = new Score();
                    s.setScore(ranking.getScore().getIF().get(year));
                    WoSRanking.Quarter qIF = rank.getQIF() != null ? rank.getQIF().get(year) : null;
                    s.setQuarter(qIF != null ? qIF.toString() : null);
                    return Optional.of(s);
                },
                this::compareScoresByPoints,
                true // carry forward to the latest ranked year — JCR lags fresh papers
        );
        if (result.bestYear.get() == 0) {
            return null;
        }
        return createScore(result);
    }

    /**
     * The fișă's I2 accepts journals "indexate în Web of Science" in ANY edition — ESCI included (the
     * standard predates the edition split, and only the IF value is used here, never a cross-edition
     * quartile placement). The shared guard is SCIE/SSCI-only, so admit the ESCI keys as well.
     */
    @Override
    protected boolean isCategoryInDomain(Domain domain, String category) {
        return super.isCategoryInDomain(domain, category)
                || ScoringCategorySupport.isEsciCategoryEligibleForDomain(domain, category);
    }

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        return new Score(); // journal papers are publication-shaped, not activity-shaped
    }

    @Override
    public String getDescription() {
        return "FSP Psihologie I2/I6 fallback: skips strict-I1 qualifiers; WoS-any-category IF (category WOS), "
                + "else >=2 recognized BDI memberships (category BDI2, S=0); formula applies 3+S.\n";
    }
}
