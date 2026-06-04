package ro.uvt.pokedex.core.model.reporting.scoring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the {@link IndicatorKind#of(String, String)} factory against every
 * production (outputTypeName, strategyName) pair surveyed in the H52 design
 * doc. After slice 11d.5, the legacy {@code Indicator.Type} / {@code Indicator.Strategy}
 * enums are gone — the form, the cache fingerprint, and the migration runner all
 * carry the legacy names as Strings and construct kinds via {@code of(...)}.
 */
class LegacyMappingTest {

    /** The 21 (outputTypeName, strategyName) pairs observed in production indicators. */
    private static List<String[]> productionCombos() {
        return List.of(
                new String[]{"PUBLICATIONS", "CS_JOURNAL"},
                new String[]{"PUBLICATIONS", "CS"},
                new String[]{"PUBLICATIONS", "CS_CONFERENCE"},
                new String[]{"PUBLICATIONS", "CS_SENSE"},
                new String[]{"PUBLICATIONS", "RIS"},
                new String[]{"PUBLICATIONS", "AIS"},
                new String[]{"PUBLICATIONS", "GENERIC_COUNT"},
                new String[]{"PUBLICATIONS", "CNCSIS"},
                new String[]{"PUBLICATIONS", "ECONOMICS_JOURNAL_AIS"},
                new String[]{"PUBLICATIONS_MAIN_AUTHOR", "IMPACT_FACTOR"},
                new String[]{"PUBLICATIONS_COAUTHOR", "IMPACT_FACTOR"},

                new String[]{"CITATIONS_EXCLUDE_SELF", "AIS"},
                new String[]{"CITATIONS_EXCLUDE_SELF", "CS"},
                new String[]{"CITATIONS_EXCLUDE_SELF", "IMPACT_FACTOR"},
                new String[]{"CITATIONS_EXCLUDE_SELF", "RIS"},

                new String[]{"GENERIC_ACTIVITIES", "GENERIC_ACTIVITY"},

                new String[]{"ACTIVITY_FORUM", "CS_CONFERENCE"},
                new String[]{"ACTIVITY_FORUM", "CS_JOURNAL"},
                new String[]{"ACTIVITY_FORUM", "CNCSIS"},
                new String[]{"ACTIVITY_UNIVERSITY", "UNI_RANKING"},
                new String[]{"ACTIVITY_EVENT", "ART_EVENT"}
        );
    }

    @Test
    void everyProductionComboMapsToExactlyOneKind() {
        for (String[] c : productionCombos()) {
            IndicatorKind kind = IndicatorKind.of(c[0], c[1]);
            assertEquals(ScoringStrategy.valueOf(c[1]), kind.strategy(),
                    "strategy mismatch for " + c[0] + "/" + c[1]);
            IndicatorKind.LegacyShape back = kind.toLegacy();
            assertEquals(c[1], back.strategyName(), "strategy round-trip for " + c[0] + "/" + c[1]);
            // Type round-trip: (PUBLICATIONS, GENERIC_COUNT) is the only intentional
            // exception (collapses GenericCount → PUBLICATIONS on the way back).
            if (!"GENERIC_COUNT".equals(c[1]) || !"PUBLICATIONS".equals(c[0])) {
                assertEquals(c[0], back.outputTypeName(), "type round-trip for " + c[0] + "/" + c[1]);
            }
        }
    }

    @Test
    void publicationsCarryAuthorRole() {
        assertEquals(AuthorRole.ALL,
                ((IndicatorKind.Publications) IndicatorKind.of("PUBLICATIONS", "CS")).role());
        assertEquals(AuthorRole.MAIN,
                ((IndicatorKind.Publications) IndicatorKind.of("PUBLICATIONS_MAIN_AUTHOR", "IMPACT_FACTOR")).role());
        assertEquals(AuthorRole.CO,
                ((IndicatorKind.Publications) IndicatorKind.of("PUBLICATIONS_COAUTHOR", "IMPACT_FACTOR")).role());
    }

    @Test
    void citationsCarryExcludeSelfFlag() {
        assertEquals(false,
                ((IndicatorKind.Citations) IndicatorKind.of("CITATIONS", "AIS")).excludeSelf());
        assertEquals(true,
                ((IndicatorKind.Citations) IndicatorKind.of("CITATIONS_EXCLUDE_SELF", "AIS")).excludeSelf());
    }

    @Test
    void activitiesCarryActivityType() {
        assertEquals(ActivityType.FORUM,
                ((IndicatorKind.Activity) IndicatorKind.of("ACTIVITY_FORUM", "CS_JOURNAL")).type());
        assertEquals(ActivityType.UNIVERSITY,
                ((IndicatorKind.Activity) IndicatorKind.of("ACTIVITY_UNIVERSITY", "UNI_RANKING")).type());
        assertEquals(ActivityType.EVENT,
                ((IndicatorKind.Activity) IndicatorKind.of("ACTIVITY_EVENT", "ART_EVENT")).type());
    }

    @Test
    void genericActivitiesOnlyPairsWithGenericActivity() {
        assertInstanceOf(IndicatorKind.GenericActivity.class,
                IndicatorKind.of("GENERIC_ACTIVITIES", "GENERIC_ACTIVITY"));
        assertThrows(IllegalArgumentException.class,
                () -> IndicatorKind.of("GENERIC_ACTIVITIES", "CS_JOURNAL"));
    }

    @Test
    void rejectsUnknownOutputTypeName() {
        assertThrows(IllegalArgumentException.class, () -> IndicatorKind.of("NOT_REAL", "CS"));
    }

    @Test
    void rejectsUnknownStrategyName() {
        assertThrows(IllegalArgumentException.class, () -> IndicatorKind.of("PUBLICATIONS", "NOT_REAL"));
    }

    @Test
    void rejectsNullArgs() {
        assertThrows(IllegalArgumentException.class, () -> IndicatorKind.of(null, "CS"));
        assertThrows(IllegalArgumentException.class, () -> IndicatorKind.of("PUBLICATIONS", null));
    }

    @Test
    void activityProjectRejectedAsUnmodelable() {
        // ACTIVITY_PROJECT existed in the legacy enum but had zero indicators in
        // production and is intentionally not modeled in v1.
        assertThrows(IllegalArgumentException.class,
                () -> IndicatorKind.of("ACTIVITY_PROJECT", "GENERIC_COUNT"));
    }

    @Test
    void scoringStrategyValueOfHandlesEveryProductionName() {
        // After slice 11d.5 the ScoringStrategy enum carries the same names the
        // production cache fingerprint uses; ScoringStrategy.valueOf is the only
        // bridge needed.
        for (String name : List.of("CS", "CS_JOURNAL", "CS_CONFERENCE", "CS_SENSE",
                "RIS", "AIS", "IMPACT_FACTOR", "ECONOMICS_JOURNAL_AIS",
                "UNI_RANKING", "CNCSIS", "ART_EVENT", "GENERIC_COUNT", "GENERIC_ACTIVITY")) {
            assertSame(ScoringStrategy.valueOf(name), ScoringStrategy.valueOf(name));
        }
    }
}
