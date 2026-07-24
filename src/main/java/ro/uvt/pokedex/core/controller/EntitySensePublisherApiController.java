package ro.uvt.pokedex.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.service.application.SensePublisherLookupFacade;

/**
 * Live SENSE publisher classification for the Add/Edit Publication wizard's Publisher field.
 * The facade delegates to the book scorer's own cached fuzzy resolution, so the badge always
 * shows exactly what scoring will decide, never a parallel approximation.
 */
@RestController
@RequestMapping("/api/entities")
@RequiredArgsConstructor
public class EntitySensePublisherApiController {

    private final SensePublisherLookupFacade sensePublisherLookupFacade;

    /** {@code matched=false} means the publisher is not SENSE-listed (scores as D/E/unlisted). */
    public record SensePublisherMatch(boolean matched, String matchedPublisher, String rank) {}

    @GetMapping("/sense-publishers")
    public ResponseEntity<SensePublisherMatch> matchPublisher(@RequestParam String q) {
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) {
            return ResponseEntity.ok(new SensePublisherMatch(false, null, null));
        }
        return ResponseEntity.ok(sensePublisherLookupFacade.matchByPublisher(query)
                .map(r -> new SensePublisherMatch(true, r.getName(), r.getRanking().name()))
                .orElse(new SensePublisherMatch(false, null, null)));
    }
}
