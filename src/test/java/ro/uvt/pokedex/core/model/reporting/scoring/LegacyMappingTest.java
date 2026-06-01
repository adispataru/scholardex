package ro.uvt.pokedex.core.model.reporting.scoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip and compatibility-table tests for the v1 scoring model. These are the gate
 * that proves every production (Indicator.Type, Indicator.Strategy) pair landed in exactly
 * one cell of the new {@link IndicatorKind} hierarchy.
 *
 * <p>The "production combinations" enumerated below are the 21 distinct pairs surveyed in
 * the H52 design doc (2026-05-30 snapshot). New entries here mean either a new combination
 * appeared in production or the migration plan needs to be revisited.
 */
class LegacyMappingTest {

    // ---------- ScoringStrategy ↔ Indicator.Strategy ----------

    @ParameterizedTest
    @EnumSource(Indicator.Strategy.class)
    void everyLegacyStrategyHasACanonicalCounterpart(Indicator.Strategy legacy) {
        ScoringStrategy promoted = ScoringStrategy.fromLegacy(legacy);
        assertNotNull(promoted);
        assertEquals(legacy, promoted.toLegacy());
    }

    @ParameterizedTest
    @EnumSource(ScoringStrategy.class)
    void everyPromotedStrategyHasALegacyCounterpart(ScoringStrategy promoted) {
        Indicator.Strategy legacy = promoted.toLegacy();
        assertNotNull(legacy);
        assertSame(promoted, ScoringStrategy.fromLegacy(legacy));
    }

    @Test
    void fromLegacyRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> ScoringStrategy.fromLegacy(null));
    }

    // ---------- IndicatorKind.fromLegacy production-combination matrix ----------

    /**
     * The 21 (OutputType × Strategy) pairs observed in production indicators on 2026-05-30.
     * The migration depends on every one of these mapping cleanly to an {@code IndicatorKind}.
     */
    private static List<Combo> productionCombos() {
        return List.of(
                // PUBLICATIONS family (one row per (strategy)) ---------------------------
                combo(Indicator.Type.PUBLICATIONS,              Indicator.Strategy.CS_JOURNAL),
                combo(Indicator.Type.PUBLICATIONS,              Indicator.Strategy.CS),
                combo(Indicator.Type.PUBLICATIONS,              Indicator.Strategy.CS_CONFERENCE),
                combo(Indicator.Type.PUBLICATIONS,              Indicator.Strategy.CS_SENSE),
                combo(Indicator.Type.PUBLICATIONS,              Indicator.Strategy.RIS),
                combo(Indicator.Type.PUBLICATIONS,              Indicator.Strategy.AIS),
                combo(Indicator.Type.PUBLICATIONS,              Indicator.Strategy.GENERIC_COUNT),
                combo(Indicator.Type.PUBLICATIONS,              Indicator.Strategy.CNCSIS),
                combo(Indicator.Type.PUBLICATIONS,              Indicator.Strategy.ECONOMICS_JOURNAL_AIS),
                combo(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR,  Indicator.Strategy.IMPACT_FACTOR),
                combo(Indicator.Type.PUBLICATIONS_COAUTHOR,     Indicator.Strategy.IMPACT_FACTOR),

                // CITATIONS family --------------------------------------------------------
                combo(Indicator.Type.CITATIONS_EXCLUDE_SELF,    Indicator.Strategy.AIS),
                combo(Indicator.Type.CITATIONS_EXCLUDE_SELF,    Indicator.Strategy.CS),
                combo(Indicator.Type.CITATIONS_EXCLUDE_SELF,    Indicator.Strategy.IMPACT_FACTOR),
                combo(Indicator.Type.CITATIONS_EXCLUDE_SELF,    Indicator.Strategy.RIS),

                // GENERIC ----------------------------------------------------------------
                combo(Indicator.Type.GENERIC_ACTIVITIES,        Indicator.Strategy.GENERIC_ACTIVITY),

                // ACTIVITY family --------------------------------------------------------
                combo(Indicator.Type.ACTIVITY_FORUM,            Indicator.Strategy.CS_CONFERENCE),
                combo(Indicator.Type.ACTIVITY_FORUM,            Indicator.Strategy.CS_JOURNAL),
                combo(Indicator.Type.ACTIVITY_FORUM,            Indicator.Strategy.CNCSIS),
                combo(Indicator.Type.ACTIVITY_UNIVERSITY,       Indicator.Strategy.UNI_RANKING),
                combo(Indicator.Type.ACTIVITY_EVENT,            Indicator.Strategy.ART_EVENT)
        );
    }

    @Test
    void everyProductionComboMapsToExactlyOneKind() {
        for (Combo c : productionCombos()) {
            IndicatorKind kind = IndicatorKind.fromLegacy(c.type(), c.strategy());
            assertNotNull(kind, "no kind for " + c);
            assertEquals(ScoringStrategy.fromLegacy(c.strategy()), kind.strategy(),
                    "strategy mismatch for " + c);
            // Round-trip
            IndicatorKind.LegacyShape back = kind.toLegacy();
            assertEquals(c.strategy(), back.strategy(), "strategy round-trip for " + c);
            // Type round-trip: only the (Publications, GENERIC_COUNT) cell intentionally
            // collapses GenericCount → PUBLICATIONS on the way back; everything else is
            // an exact match.
            if (kind instanceof IndicatorKind.GenericCount) {
                assertEquals(Indicator.Type.PUBLICATIONS, back.type());
            } else {
                assertEquals(c.type(), back.type(), "type round-trip for " + c);
            }
        }
    }

    @Test
    void publicationAuthorRolesMapCorrectly() {
        assertEquals(AuthorRole.ALL,
                ((IndicatorKind.Publications) IndicatorKind.fromLegacy(
                        Indicator.Type.PUBLICATIONS, Indicator.Strategy.CS_JOURNAL)).role());
        assertEquals(AuthorRole.MAIN,
                ((IndicatorKind.Publications) IndicatorKind.fromLegacy(
                        Indicator.Type.PUBLICATIONS_MAIN_AUTHOR, Indicator.Strategy.IMPACT_FACTOR)).role());
        assertEquals(AuthorRole.CO,
                ((IndicatorKind.Publications) IndicatorKind.fromLegacy(
                        Indicator.Type.PUBLICATIONS_COAUTHOR, Indicator.Strategy.IMPACT_FACTOR)).role());
    }

    @Test
    void citationsCarryExcludeSelfFlag() {
        IndicatorKind.Citations excl = (IndicatorKind.Citations) IndicatorKind.fromLegacy(
                Indicator.Type.CITATIONS_EXCLUDE_SELF, Indicator.Strategy.AIS);
        assertTrue(excl.excludeSelf());

        IndicatorKind.Citations incl = (IndicatorKind.Citations) IndicatorKind.fromLegacy(
                Indicator.Type.CITATIONS, Indicator.Strategy.AIS);
        assertFalse(incl.excludeSelf());
    }

    @Test
    void activityTypesMapByLegacyEnumName() {
        assertEquals(ActivityType.FORUM,
                ((IndicatorKind.Activity) IndicatorKind.fromLegacy(
                        Indicator.Type.ACTIVITY_FORUM, Indicator.Strategy.CS_JOURNAL)).type());
        assertEquals(ActivityType.UNIVERSITY,
                ((IndicatorKind.Activity) IndicatorKind.fromLegacy(
                        Indicator.Type.ACTIVITY_UNIVERSITY, Indicator.Strategy.UNI_RANKING)).type());
        assertEquals(ActivityType.EVENT,
                ((IndicatorKind.Activity) IndicatorKind.fromLegacy(
                        Indicator.Type.ACTIVITY_EVENT, Indicator.Strategy.ART_EVENT)).type());
    }

    @Test
    void genericActivitiesProducesGenericActivityKind() {
        IndicatorKind kind = IndicatorKind.fromLegacy(
                Indicator.Type.GENERIC_ACTIVITIES, Indicator.Strategy.GENERIC_ACTIVITY);
        assertInstanceOf(IndicatorKind.GenericActivity.class, kind);
        assertEquals(ScoringStrategy.GENERIC_ACTIVITY, kind.strategy());
    }

    @Test
    void genericActivitiesRejectsAnyOtherStrategy() {
        assertThrows(IllegalArgumentException.class,
                () -> IndicatorKind.fromLegacy(
                        Indicator.Type.GENERIC_ACTIVITIES, Indicator.Strategy.CS_JOURNAL));
    }

    @Test
    void unusedActivityProjectIsRejectedAsImpossibleByConstruction() {
        // ACTIVITY_PROJECT has 0 indicators in production. The migration treats it as a
        // dead enum value; v1 doesn't model it. Saving an indicator with this output type
        // should fail at parse time, not silently scope-bind to something arbitrary.
        assertThrows(IllegalArgumentException.class,
                () -> IndicatorKind.fromLegacy(
                        Indicator.Type.ACTIVITY_PROJECT, Indicator.Strategy.GENERIC_ACTIVITY));
    }

    @Test
    void fromLegacyRejectsNulls() {
        assertThrows(IllegalArgumentException.class,
                () -> IndicatorKind.fromLegacy(null, Indicator.Strategy.CS_JOURNAL));
        assertThrows(IllegalArgumentException.class,
                () -> IndicatorKind.fromLegacy(Indicator.Type.PUBLICATIONS, null));
    }

    // ---------- YearRangeSpec.parse ----------

    @Test
    void yearRangeParseHandlesAllProductionShapes() {
        assertEquals(new YearRangeSpec.AllYears(), YearRangeSpec.parse("*"));
        assertEquals(new YearRangeSpec.AllYears(), YearRangeSpec.parse(null));
        assertEquals(new YearRangeSpec.AllYears(), YearRangeSpec.parse("  "));
        assertEquals(new YearRangeSpec.Absolute(2015, 2026), YearRangeSpec.parse("2015->2026"));
        assertEquals(new YearRangeSpec.Absolute(2015, 2025), YearRangeSpec.parse("2015-2025"));
        assertEquals(new YearRangeSpec.Absolute(2018, 2025), YearRangeSpec.parse("2018->2025"));
    }

    @Test
    void yearRangeRejectsInverted() {
        assertThrows(IllegalArgumentException.class, () -> new YearRangeSpec.Absolute(2025, 2018));
    }

    @Test
    void yearRangeRejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> YearRangeSpec.parse("notanumber->2020"));
        assertThrows(IllegalArgumentException.class, () -> YearRangeSpec.parse("2020->"));
        assertThrows(IllegalArgumentException.class, () -> YearRangeSpec.parse("IY"));
    }

    // ---------- ScoreYearRangeSpec.parse ----------

    @Test
    void scoreYearRangeParseHandlesAllProductionShapes() {
        assertEquals(new ScoreYearRangeSpec.ItemYear(), ScoreYearRangeSpec.parse("IY"));
        assertEquals(new ScoreYearRangeSpec.ItemYear(), ScoreYearRangeSpec.parse(null));
        assertEquals(new ScoreYearRangeSpec.AllYears(), ScoreYearRangeSpec.parse("*"));
        assertEquals(new ScoreYearRangeSpec.Absolute(2019, 2023), ScoreYearRangeSpec.parse("2019->2023"));
    }

    @Test
    void scoreYearRangeRejectsLegacyIYArithmetic() {
        // IY+2, IY-1 etc. are unused in production data and explicitly NOT carried to v1.
        // They should fall through to the integer-parse path and fail clearly.
        assertThrows(IllegalArgumentException.class, () -> ScoreYearRangeSpec.parse("IY+2"));
        assertThrows(IllegalArgumentException.class, () -> ScoreYearRangeSpec.parse("IY-1->IY+2"));
    }

    // ---------- Selector ----------

    @Test
    void selectorMapsLegacyNullAsAll() {
        assertEquals(new Selector.All(), Selector.fromLegacy(null));
    }

    @Test
    void selectorRoundTrip() {
        // ALL and null are both valid legacy representations of "no selector". toLegacy()
        // normalises to null. The round-trip we care about is at the promoted layer:
        // the post-promotion value survives a round trip through whatever legacy form it
        // serialises to.
        for (Indicator.Selector legacy : Indicator.Selector.values()) {
            Selector promoted = Selector.fromLegacy(legacy);
            assertNotNull(promoted);
            assertEquals(promoted, Selector.fromLegacy(promoted.toLegacy()),
                    "promoted selector must survive a legacy round-trip");
        }
        // The "null = ALL" convention on the way back: an All selector serialises to null,
        // and null deserialises back to All.
        assertNull(new Selector.All().toLegacy());
        assertEquals(new Selector.All(), Selector.fromLegacy(null));
    }

    @Test
    void selectorRejectsNonPositiveN() {
        assertThrows(IllegalArgumentException.class, () -> new Selector.TopN(0));
        assertThrows(IllegalArgumentException.class, () -> new Selector.TopN(-1));
    }

    // ---------- helpers ----------

    private static Combo combo(Indicator.Type t, Indicator.Strategy s) { return new Combo(t, s); }
    private record Combo(Indicator.Type type, Indicator.Strategy strategy) {}
}
