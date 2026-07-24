package ro.uvt.pokedex.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.service.application.CoreConferenceLookupFacade;

import java.util.List;
import java.util.Map;

/**
 * Conference picker autocomplete for activity {@code FORUM_NAME} fields (mirrors
 * {@link EntityProjectApiController}). Searches the CORE conference rankings directly — CORE is the
 * authority the CS conference scorer consults, and it carries the acronym the canonical forum table
 * lacks. The picker's chosen value is stored as plain {@code "ACRONYM — Full Name"} text in the
 * activity's FORUM_NAME, which the scorer's existing confidence machinery resolves (and, for
 * same-acronym CORE collisions, disambiguates by name-token overlap) with no scoring changes.
 */
@RestController
@Validated
@RequestMapping("/api/entities")
@RequiredArgsConstructor
public class EntityCoreConferenceApiController {

    private final CoreConferenceLookupFacade coreConferenceLookupFacade;

    /** One picker suggestion; {@code latestRank}/{@code latestYear} let same-acronym rows disambiguate. */
    public record CoreConferenceSuggestion(String acronym, String name, String latestRank, Integer latestYear) {}

    @GetMapping("/core-conferences")
    public ResponseEntity<List<CoreConferenceSuggestion>> searchCoreConferences(@RequestParam String q) {
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) {
            return ResponseEntity.ok(List.of());
        }
        List<CoreConferenceSuggestion> suggestions = coreConferenceLookupFacade
                .searchByAcronymOrName(query)
                .stream()
                .map(EntityCoreConferenceApiController::toSuggestion)
                .toList();
        return ResponseEntity.ok(suggestions);
    }

    private static CoreConferenceSuggestion toSuggestion(CoreConferenceRanking ranking) {
        String latestRank = null;
        Integer latestYear = null;
        Map<Integer, CoreConferenceRanking.YearlyRanking> editions = ranking.sortedYearlyRankings();
        for (Map.Entry<Integer, CoreConferenceRanking.YearlyRanking> entry : editions.entrySet()) {
            if (latestYear == null || entry.getKey() > latestYear) {
                latestYear = entry.getKey();
                latestRank = entry.getValue() != null && entry.getValue().getRank() != null
                        ? entry.getValue().getRank().toString()
                        : null;
            }
        }
        return new CoreConferenceSuggestion(ranking.getAcronym(), ranking.getName(), latestRank, latestYear);
    }
}
