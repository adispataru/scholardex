package ro.uvt.pokedex.core.service.model;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.UniversityRanking;
import ro.uvt.pokedex.core.repository.URAPUniversityRankingRepository;
import ro.uvt.pokedex.core.repository.UniversityRankingRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * H83 S3 — best-of world-university-rank resolution across URAP, ARWU and QS, per the OM 3019/2025
 * footnote *3 ("Cele mai bune poziții conform clasamentelor…", identical in the 2016 standard): the
 * candidate scores by the BEST (minimum) position any of the three rankings gives the university.
 *
 * <p>Per source the rank is taken at the requested year, falling back to the source's closest data year
 * (tie → earlier year, matching the URAP behavior shipped for old visits). Name resolution stays
 * exact-ignore-case per source — cross-source aliasing is deliberately out of scope (the S4 picker is
 * the fix for name misses).</p>
 */
@Service
@RequiredArgsConstructor
public class UniversityRankingLookupService {

    private final URAPUniversityRankingRepository urapUniversityRankingRepository;
    private final UniversityRankingRepository universityRankingRepository;

    /** The winning position and where it came from — provenance feeds the drilldown/scoringInfo. */
    public record BestRank(int rank, String source, int dataYear, String rankBand) {}

    public Optional<BestRank> bestRank(String name, int year) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        List<BestRank> candidates = new ArrayList<>();

        urapUniversityRankingRepository.findByNameIgnoreCase(name).stream().findFirst()
                .flatMap(urap -> closestUrapYear(urap, year))
                .ifPresent(candidates::add);

        for (UniversityRanking generic : universityRankingRepository.findByNameIgnoreCase(name)) {
            closestGenericYear(generic, year).ifPresent(candidates::add);
        }

        return candidates.stream().min(
                Comparator.comparingInt(BestRank::rank)
                        .thenComparingInt(c -> Math.abs(c.dataYear() - year))
                        .thenComparing(BestRank::source));
    }

    private Optional<BestRank> closestUrapYear(URAPUniversityRanking ranking, int year) {
        Map<Integer, URAPUniversityRanking.Score> scores = ranking.getScores();
        if (scores == null || scores.isEmpty()) {
            return Optional.empty();
        }
        Integer chosen = closestYear(scores.keySet(), year);
        if (chosen == null || scores.get(chosen) == null || scores.get(chosen).getRank() <= 0) {
            return Optional.empty();
        }
        int rank = scores.get(chosen).getRank();
        return Optional.of(new BestRank(rank, "URAP", chosen, String.valueOf(rank)));
    }

    private Optional<BestRank> closestGenericYear(UniversityRanking ranking, int year) {
        Map<Integer, UniversityRanking.RankEntry> ranks = ranking.getRanks();
        if (ranks == null || ranks.isEmpty()) {
            return Optional.empty();
        }
        Integer chosen = closestYear(ranks.keySet(), year);
        if (chosen == null || ranks.get(chosen) == null || ranks.get(chosen).getRank() <= 0) {
            return Optional.empty();
        }
        UniversityRanking.RankEntry entry = ranks.get(chosen);
        return Optional.of(new BestRank(entry.getRank(), ranking.getSource(), chosen, entry.getRankBand()));
    }

    /** Exact year when present, else the closest data year; ties prefer the earlier year. */
    private static Integer closestYear(Iterable<Integer> dataYears, int year) {
        Integer closest = null;
        for (Integer dataYear : dataYears) {
            if (dataYear == null) {
                continue;
            }
            if (dataYear == year) {
                return dataYear;
            }
            if (closest == null
                    || Math.abs(dataYear - year) < Math.abs(closest - year)
                    || (Math.abs(dataYear - year) == Math.abs(closest - year) && dataYear < closest)) {
                closest = dataYear;
            }
        }
        return closest;
    }
}
