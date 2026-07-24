package ro.uvt.pokedex.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.service.application.UniversityLookupFacade;

import java.util.List;

/**
 * University picker autocomplete for activity {@code UNIVERSITY_NAME} fields (H83 S4, mirrors
 * {@link EntityCoreConferenceApiController}). Suggestions come from the union of ranking names
 * (URAP/ARWU/QS); each name variant lists the rankings that know that exact spelling, since scoring
 * resolves names exact-per-source.
 */
@RestController
@RequestMapping("/api/entities")
@RequiredArgsConstructor
public class EntityUniversityApiController {

    private final UniversityLookupFacade universityLookupFacade;

    @GetMapping("/universities")
    public ResponseEntity<List<UniversityLookupFacade.UniversitySuggestion>> searchUniversities(
            @RequestParam String q) {
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(universityLookupFacade.search(query));
    }
}
