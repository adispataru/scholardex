package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.UniversityRanking;
import ro.uvt.pokedex.core.repository.URAPUniversityRankingRepository;
import ro.uvt.pokedex.core.repository.UniversityRankingRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H83 S4 — university picker autocomplete over the union of ranking names (URAP + ARWU + QS), mirroring
 * {@link CoreConferenceLookupFacade}. Name variants are deliberately kept SEPARATE (e.g. the four
 * Aix-Marseille spellings): scoring resolves exact-per-source, so each suggestion lists which rankings
 * know that exact spelling — the researcher picks the variant with the coverage they need.
 */
@Service
@RequiredArgsConstructor
public class UniversityLookupFacade {

    private static final int MAX_SUGGESTIONS = 12;

    private final URAPUniversityRankingRepository urapUniversityRankingRepository;
    private final UniversityRankingRepository universityRankingRepository;

    /** One suggestion per exact name variant; {@code rankings} = "SOURCE band (year)" summaries. */
    public record UniversitySuggestion(String name, List<String> rankings) {}

    public List<UniversitySuggestion> search(String query) {
        Map<String, List<String>> byName = new LinkedHashMap<>();

        for (URAPUniversityRanking urap : urapUniversityRankingRepository
                .findTop10ByNameContainingIgnoreCaseOrderByNameAsc(query)) {
            latestUrapSummary(urap).ifPresent(s ->
                    byName.computeIfAbsent(urap.getName(), k -> new ArrayList<>()).add(s));
        }
        for (UniversityRanking generic : universityRankingRepository
                .findTop20ByNameContainingIgnoreCaseOrderByNameAsc(query)) {
            latestGenericSummary(generic).ifPresent(s ->
                    byName.computeIfAbsent(generic.getName(), k -> new ArrayList<>()).add(s));
        }

        return byName.entrySet().stream()
                .limit(MAX_SUGGESTIONS)
                .map(e -> new UniversitySuggestion(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }

    private static java.util.Optional<String> latestUrapSummary(URAPUniversityRanking urap) {
        if (urap.getScores() == null || urap.getScores().isEmpty()) {
            return java.util.Optional.empty();
        }
        return urap.getScores().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().getRank() > 0)
                .max(Comparator.comparingInt(Map.Entry::getKey))
                .map(e -> "URAP #" + e.getValue().getRank() + " (" + e.getKey() + ")");
    }

    private static java.util.Optional<String> latestGenericSummary(UniversityRanking generic) {
        if (generic.getRanks() == null || generic.getRanks().isEmpty()) {
            return java.util.Optional.empty();
        }
        return generic.getRanks().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().getRank() > 0)
                .max(Comparator.comparingInt(Map.Entry::getKey))
                .map(e -> generic.getSource() + " " + e.getValue().getRankBand() + " (" + e.getKey() + ")");
    }
}
