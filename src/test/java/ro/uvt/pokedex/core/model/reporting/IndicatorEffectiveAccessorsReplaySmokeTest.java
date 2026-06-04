package ro.uvt.pokedex.core.model.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;
import ro.uvt.pokedex.core.model.reporting.scoring.Selector;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * H52 slice 3 bridge smoke test. Stands in for the full historical replay against
 * 224 {@code GroupIndividualReportRun} + 126 {@code UserIndividualReportRun} rows
 * (that fixture lands in commit 2, per the design doc), and gives us a tight loop
 * that catches the most realistic failure surface today: any historical indicator
 * shape blowing up when the new {@code getEffective*} helpers touch it.
 *
 * <p>Covers the cartesian product of:</p>
 * <ul>
 *   <li>21 production {@code (Type, Strategy)} pairs surveyed on 2026-05-30</li>
 *   <li>Both common {@code yearRange} dialects: {@code "*"} (37/42 indicators) and an
 *       explicit absolute range</li>
 *   <li>All three {@code scoreYearRange} dialects: {@code "IY"} (31/42), absolute,
 *       and the legacy null</li>
 *   <li>Both legacy {@link Indicator.Selector} values plus the null default</li>
 * </ul>
 *
 * <p>The assertion contract is intentionally weak: <strong>no exception, non-null kind,
 * non-null year specs.</strong> Equality of computed scores against historical caches is
 * the job of the slice-4 fixture replay; this test exists so we notice immediately if
 * an effective getter starts throwing on a shape we already shipped.</p>
 */
class IndicatorEffectiveAccessorsReplaySmokeTest {

    private record Combo(String typeName, String strategyName) {}

    private static List<Combo> productionCombos() {
        // Mirrors LegacyMappingTest#productionCombos(); duplicated here so a refactor
        // in the mapping test doesn't silently shrink this smoke surface.
        return List.of(
                new Combo("PUBLICATIONS", "CS_JOURNAL"),
                new Combo("PUBLICATIONS", "CS"),
                new Combo("PUBLICATIONS", "CS_CONFERENCE"),
                new Combo("PUBLICATIONS", "CS_SENSE"),
                new Combo("PUBLICATIONS", "RIS"),
                new Combo("PUBLICATIONS", "AIS"),
                new Combo("PUBLICATIONS", "GENERIC_COUNT"),
                new Combo("PUBLICATIONS", "CNCSIS"),
                new Combo("PUBLICATIONS", "ECONOMICS_JOURNAL_AIS"),
                new Combo("PUBLICATIONS_MAIN_AUTHOR", "IMPACT_FACTOR"),
                new Combo("PUBLICATIONS_COAUTHOR", "IMPACT_FACTOR"),
                new Combo("CITATIONS_EXCLUDE_SELF", "AIS"),
                new Combo("CITATIONS_EXCLUDE_SELF", "CS"),
                new Combo("CITATIONS_EXCLUDE_SELF", "IMPACT_FACTOR"),
                new Combo("CITATIONS_EXCLUDE_SELF", "RIS"),
                new Combo("GENERIC_ACTIVITIES", "GENERIC_ACTIVITY"),
                new Combo("ACTIVITY_FORUM", "CS_CONFERENCE"),
                new Combo("ACTIVITY_FORUM", "CS_JOURNAL"),
                new Combo("ACTIVITY_FORUM", "CNCSIS"),
                new Combo("ACTIVITY_UNIVERSITY", "UNI_RANKING"),
                new Combo("ACTIVITY_EVENT", "ART_EVENT")
        );
    }

    private static final List<String> YEAR_RANGES = List.of("*", "2017->2025");
    private static final List<String> SCORE_YEAR_RANGES = java.util.Arrays.asList("IY", "2018->2024", null);
    private static final List<String> SELECTORS =
            java.util.Arrays.asList("ALL", "TOP_10", null);

    @Test
    void everyHistoricalShapeRoundTripsThroughEffectiveGetters() {
        for (Combo c : productionCombos()) {
            for (String yr : YEAR_RANGES) {
                for (String syr : SCORE_YEAR_RANGES) {
                    for (String sel : SELECTORS) {
                        Indicator ind = new Indicator();
                        ind.setOutputType(c.typeName());
                        ind.setScoringStrategy(c.strategyName());
                        ind.setYearRange(yr);
                        ind.setScoreYearRange(syr);
                        ind.setSelector(sel);

                        String label = "combo=" + c + ", yr=" + yr + ", syr=" + syr + ", sel=" + sel;

                        assertDoesNotThrow(() -> {
                            IndicatorKind kind = ind.getEffectiveKind();
                            YearRangeSpec yearSpec = ind.getEffectiveYearRange();
                            ScoreYearRangeSpec scoreSpec = ind.getEffectiveScoreYearRange();
                            Selector selectorSpec = ind.getEffectiveSelector();

                            assertNotNull(kind, "kind null for " + label);
                            assertNotNull(kind.strategy(), "strategy null for " + label);
                            assertNotNull(yearSpec, "yearSpec null for " + label);
                            assertNotNull(scoreSpec, "scoreSpec null for " + label);
                            assertNotNull(selectorSpec, "selectorSpec null for " + label);
                        }, "effective getters threw on " + label);
                    }
                }
            }
        }
    }

    /**
     * Belt-and-braces: when the v1 fields have already been populated by the
     * migration runner, getEffective* must return them verbatim (no re-derivation
     * from legacy). Protects against an accidental "always recompute" regression.
     */
    @Test
    void migratedIndicatorsBypassLegacyDerivation() {
        Indicator ind = new Indicator();
        // Legacy fields set to something *different* from the v1 fields so we can
        // tell which path returned.
        ind.setOutputType("PUBLICATIONS");
        ind.setScoringStrategy("CS_JOURNAL");
        ind.setYearRange("2000->2005");
        ind.setScoreYearRange("IY");
        ind.setSelector("ALL");

        ind.setKind(new IndicatorKind.Citations(true,
                ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy.RIS));
        ind.setYearRangeSpec(new YearRangeSpec.Absolute(2020, 2025));
        ind.setScoreYearRangeSpec(new ScoreYearRangeSpec.Absolute(2022, 2024));
        ind.setSelectorSpec(new Selector.TopN(5));

        assertNotNull(ind.getEffectiveKind());
        assert ind.getEffectiveKind() instanceof IndicatorKind.Citations;
        assert ind.getEffectiveYearRange().equals(new YearRangeSpec.Absolute(2020, 2025));
        assert ind.getEffectiveScoreYearRange().equals(new ScoreYearRangeSpec.Absolute(2022, 2024));
        assert ind.getEffectiveSelector().equals(new Selector.TopN(5));
    }
}
