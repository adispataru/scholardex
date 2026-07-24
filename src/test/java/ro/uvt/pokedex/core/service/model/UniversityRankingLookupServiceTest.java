package ro.uvt.pokedex.core.service.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.UniversityRanking;
import ro.uvt.pokedex.core.repository.URAPUniversityRankingRepository;
import ro.uvt.pokedex.core.repository.UniversityRankingRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** H83 S3 — best-of (minimum) rank across URAP/ARWU/QS with per-source closest-year fallback. */
@ExtendWith(MockitoExtension.class)
class UniversityRankingLookupServiceTest {

    @Mock
    private URAPUniversityRankingRepository urapRepo;
    @Mock
    private UniversityRankingRepository genericRepo;

    private UniversityRankingLookupService service() {
        return new UniversityRankingLookupService(urapRepo, genericRepo);
    }

    private static URAPUniversityRanking urap(Map<Integer, Integer> ranksByYear) {
        URAPUniversityRanking u = new URAPUniversityRanking();
        Map<Integer, URAPUniversityRanking.Score> scores = new HashMap<>();
        ranksByYear.forEach((y, r) -> {
            URAPUniversityRanking.Score s = new URAPUniversityRanking.Score();
            s.setRank(r);
            scores.put(y, s);
        });
        u.setScores(scores);
        return u;
    }

    private static UniversityRanking generic(String source, Map<Integer, int[]> years, Map<Integer, String> bands) {
        UniversityRanking u = new UniversityRanking();
        u.setSource(source);
        Map<Integer, UniversityRanking.RankEntry> ranks = new HashMap<>();
        years.forEach((y, r) -> {
            UniversityRanking.RankEntry e = new UniversityRanking.RankEntry();
            e.setRank(r[0]);
            e.setRankBand(bands.getOrDefault(y, String.valueOf(r[0])));
            ranks.put(y, e);
        });
        u.setRanks(ranks);
        return u;
    }

    @Test
    void minimumRankAcrossSourcesWins() {
        // The Aix-Marseille 2015 shape: URAP 77, ARWU 101-150, QS 411-420 -> URAP wins here.
        when(urapRepo.findByNameIgnoreCase("Aix")).thenReturn(List.of(urap(Map.of(2015, 77))));
        when(genericRepo.findByNameIgnoreCase("Aix")).thenReturn(List.of(
                generic("ARWU", Map.of(2015, new int[]{101}), Map.of(2015, "101-150")),
                generic("QS", Map.of(2015, new int[]{411}), Map.of(2015, "411-420"))));

        UniversityRankingLookupService.BestRank best = service().bestRank("Aix", 2015).orElseThrow();

        assertEquals(77, best.rank());
        assertEquals("URAP", best.source());
    }

    @Test
    void arwuWinsWhenUrapHasNoCoverage() {
        // Pre-URAP era (2005): ARWU alone covers it — the whole point of multi-source.
        when(urapRepo.findByNameIgnoreCase("Pisa")).thenReturn(List.of(urap(Map.of(2010, 177))));
        when(genericRepo.findByNameIgnoreCase("Pisa")).thenReturn(List.of(
                generic("ARWU", Map.of(2005, new int[]{151}), Map.of(2005, "151-200"))));

        UniversityRankingLookupService.BestRank best = service().bestRank("Pisa", 2005).orElseThrow();

        assertEquals(151, best.rank());
        assertEquals("ARWU", best.source());
        assertEquals(2005, best.dataYear());
        assertEquals("151-200", best.rankBand());
    }

    @Test
    void perSourceClosestYearWithEarlierYearTie() {
        // 2021 request, URAP data at 2020/2022 — tie prefers the earlier year (pinned URAP behavior).
        when(urapRepo.findByNameIgnoreCase("UVT")).thenReturn(List.of(urap(Map.of(2020, 100, 2022, 600))));
        when(genericRepo.findByNameIgnoreCase("UVT")).thenReturn(List.of());

        UniversityRankingLookupService.BestRank best = service().bestRank("UVT", 2021).orElseThrow();

        assertEquals(100, best.rank());
        assertEquals(2020, best.dataYear());
    }

    @Test
    void equalRanksPreferTheCloserDataYear() {
        when(urapRepo.findByNameIgnoreCase("X")).thenReturn(List.of(urap(Map.of(2010, 200))));
        when(genericRepo.findByNameIgnoreCase("X")).thenReturn(List.of(
                generic("ARWU", Map.of(2015, new int[]{200}), Map.of())));

        UniversityRankingLookupService.BestRank best = service().bestRank("X", 2015).orElseThrow();

        assertEquals("ARWU", best.source()); // same rank, 2015 data is closer than 2010
    }

    @Test
    void unknownNameOrBlankResolvesEmpty() {
        when(urapRepo.findByNameIgnoreCase("Ghost")).thenReturn(List.of());
        when(genericRepo.findByNameIgnoreCase("Ghost")).thenReturn(List.of());
        assertTrue(service().bestRank("Ghost", 2020).isEmpty());
        assertTrue(service().bestRank("  ", 2020).isEmpty());
        assertEquals(Optional.empty(), service().bestRank(null, 2020));
    }
}
