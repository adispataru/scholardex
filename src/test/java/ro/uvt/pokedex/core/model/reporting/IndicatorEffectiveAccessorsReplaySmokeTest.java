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

    private record Combo(Indicator.Type type, Indicator.Strategy strategy) {}

    private static List<Combo> productionCombos() {
        // Mirrors LegacyMappingTest#productionCombos(); duplicated here so a refactor
        // in the mapping test doesn't silently shrink this smoke surface.
        return List.of(
                new Combo(Indicator.Type.PUBLICATIONS, Indicator.Strategy.CS_JOURNAL),
                new Combo(Indicator.Type.PUBLICATIONS, Indicator.Strategy.CS),
                new Combo(Indicator.Type.PUBLICATIONS, Indicator.Strategy.CS_CONFERENCE),
                new Combo(Indicator.Type.PUBLICATIONS, Indicator.Strategy.CS_SENSE),
                new Combo(Indicator.Type.PUBLICATIONS, Indicator.Strategy.RIS),
                new Combo(Indicator.Type.PUBLICATIONS, Indicator.Strategy.AIS),
                new Combo(Indicator.Type.PUBLICATIONS, Indicator.Strategy.GENERIC_COUNT),
                new Combo(Indicator.Type.PUBLICATIONS, Indicator.Strategy.CNCSIS),
                new Combo(Indicator.Type.PUBLICATIONS, Indicator.Strategy.ECONOMICS_JOURNAL_AIS),
                new Combo(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR, Indicator.Strategy.IMPACT_FACTOR),
                new Combo(Indicator.Type.PUBLICATIONS_COAUTHOR, Indicator.Strategy.IMPACT_FACTOR),
                new Combo(Indicator.Type.CITATIONS_EXCLUDE_SELF, Indicator.Strategy.AIS),
                new Combo(Indicator.Type.CITATIONS_EXCLUDE_SELF, Indicator.Strategy.CS),
                new Combo(Indicator.Type.CITATIONS_EXCLUDE_SELF, Indicator.Strategy.IMPACT_FACTOR),
                new Combo(Indicator.Type.CITATIONS_EXCLUDE_SELF, Indicator.Strategy.RIS),
                new Combo(Indicator.Type.GENERIC_ACTIVITIES, Indicator.Strategy.GENERIC_ACTIVITY),
                new Combo(Indicator.Type.ACTIVITY_FORUM, Indicator.Strategy.CS_CONFERENCE),
                new Combo(Indicator.Type.ACTIVITY_FORUM, Indicator.Strategy.CS_JOURNAL),
                new Combo(Indicator.Type.ACTIVITY_FORUM, Indicator.Strategy.CNCSIS),
                new Combo(Indicator.Type.ACTIVITY_UNIVERSITY, Indicator.Strategy.UNI_RANKING),
                new Combo(Indicator.Type.ACTIVITY_EVENT, Indicator.Strategy.ART_EVENT)
        );
    }

    private static final List<String> YEAR_RANGES = List.of("*", "2017->2025");
    private static final List<String> SCORE_YEAR_RANGES = java.util.Arrays.asList("IY", "2018->2024", null);
    private static final List<Indicator.Selector> SELECTORS =
            java.util.Arrays.asList(Indicator.Selector.ALL, Indicator.Selector.TOP_10, null);

    @Test
    void everyHistoricalShapeRoundTripsThroughEffectiveGetters() {
        for (Combo c : productionCombos()) {
            for (String yr : YEAR_RANGES) {
                for (String syr : SCORE_YEAR_RANGES) {
                    for (Indicator.Selector sel : SELECTORS) {
                        Indicator ind = new Indicator();
                        ind.setOutputType(c.type());
                        ind.setScoringStrategy(c.strategy());
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
        ind.setOutputType(Indicator.Type.PUBLICATIONS);
        ind.setScoringStrategy(Indicator.Strategy.CS_JOURNAL);
        ind.setYearRange("2000->2005");
        ind.setScoreYearRange("IY");
        ind.setSelector(Indicator.Selector.ALL);

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
