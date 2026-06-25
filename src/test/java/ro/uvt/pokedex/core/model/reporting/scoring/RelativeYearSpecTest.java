package ro.uvt.pokedex.core.model.reporting.scoring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H60: the two new relative year specs — resolution, boundaries, codec round-trip. */
class RelativeYearSpecTest {

    // --- YearRangeSpec.PreviousNYears (article inclusion) ---

    @Test
    void previousNYearsIncludesTMinusNThroughTMinus1() {
        YearRangeSpec spec = new YearRangeSpec.PreviousNYears(7);
        int t = 2026;
        assertFalse(spec.includes(t, t), "the reference year itself is excluded");
        assertTrue(spec.includes(t - 1, t), "t-1 included");
        assertTrue(spec.includes(t - 7, t), "t-7 (oldest) included");
        assertFalse(spec.includes(t - 8, t), "t-8 too old");
        assertFalse(spec.includes(t + 1, t), "future excluded");
    }

    @Test
    void absoluteAndAllYearsInclusionUnchanged() {
        assertTrue(new YearRangeSpec.AllYears().includes(1980, 2026));
        assertTrue(new YearRangeSpec.Absolute(2018, 2025).includes(2020, 2026));
        assertFalse(new YearRangeSpec.Absolute(2018, 2025).includes(2026, 2026));
    }

    @Test
    void previousNYearsRoundTripsThroughParse() {
        assertEquals(new YearRangeSpec.PreviousNYears(7), YearRangeSpec.parse("PREV:7"));
        assertThrows(IllegalArgumentException.class, () -> new YearRangeSpec.PreviousNYears(0));
    }

    // --- ScoreYearRangeSpec.LatestNRankings (ranking-list selection) ---

    @Test
    void latestNRankingsTakesTheNMostRecentYearsAtOrBeforeReferenceYear() {
        ScoreYearRangeSpec spec = new ScoreYearRangeSpec.LatestNRankings(1);
        var ctx = new ScoreYearRangeSpec.ResolutionContext(2020, 2024, List.of(2019, 2021, 2023, 2025));
        // 2025 is after referenceYear 2024 → excluded; latest ≤ 2024 is 2023.
        assertEquals(List.of(2023), spec.allowedYears(ctx));

        var ctx2 = new ScoreYearRangeSpec.ResolutionContext(2020, 2024, List.of(2019, 2021, 2023));
        assertEquals(List.of(2023, 2021), new ScoreYearRangeSpec.LatestNRankings(2).allowedYears(ctx2));
    }

    @Test
    void latestNRankingsIsEmptyWithoutContext() {
        // The legacy no-context path can't resolve it → empty (not a stale fallback).
        assertTrue(new ScoreYearRangeSpec.LatestNRankings(1).allowedYears(2020).isEmpty());
        assertTrue(new ScoreYearRangeSpec.LatestNRankings(1)
                .allowedYears(new ScoreYearRangeSpec.ResolutionContext(2020, null, List.of(2023))).isEmpty());
        assertTrue(new ScoreYearRangeSpec.LatestNRankings(1)
                .allowedYears(new ScoreYearRangeSpec.ResolutionContext(2020, 2024, List.of())).isEmpty());
    }

    @Test
    void existingScoreShapesUnchangedAndAllYearsCapsAtReferenceYear() {
        assertEquals(List.of(2020), new ScoreYearRangeSpec.ItemYear().allowedYears(2020));
        assertEquals(List.of(2018, 2019, 2020), new ScoreYearRangeSpec.Absolute(2018, 2020).allowedYears(2020));
        // AllYears with a referenceYear is deterministic (caps at it) instead of now().
        List<Integer> capped = new ScoreYearRangeSpec.AllYears()
                .allowedYears(new ScoreYearRangeSpec.ResolutionContext(2020, 2000, null));
        assertEquals(2000, capped.get(capped.size() - 1));
        assertEquals(1990, capped.get(0));
    }

    @Test
    void latestNRankingsRoundTripsThroughParse() {
        assertEquals(new ScoreYearRangeSpec.LatestNRankings(1), ScoreYearRangeSpec.parse("LATEST:1"));
        assertThrows(IllegalArgumentException.class, () -> new ScoreYearRangeSpec.LatestNRankings(0));
    }
}
