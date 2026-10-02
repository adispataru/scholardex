package ro.uvt.pokedex.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.service.application.ArtisticEventLookupFacade;

import java.util.List;

/**
 * H142 slice 3 — the event picker of a declared artistic performance ({@code EVENT_NAME}): ranked events and their
 * spellings, and names already waiting for the experts. Free text stays valid: an unknown name is a proposal.
 */
@RestController
@RequestMapping("/api/entities")
@RequiredArgsConstructor
public class EntityArtisticEventApiController {

    private final ArtisticEventLookupFacade lookup;

    @GetMapping("/artistic-events")
    public ResponseEntity<List<ArtisticEventLookupFacade.EventSuggestion>> search(@RequestParam String q) {
        return ResponseEntity.ok(lookup.search(q));
    }
}
