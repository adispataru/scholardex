package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.ArtisticEventLookupFacade;

import java.util.Map;

/**
 * H142 slice 3 — what the registry of artistic events says about the event each of the researcher's records names:
 * its rank, or that the experts have not ranked it (or rejected the name). Read-only.
 */
@RestController
@RequestMapping("/user/workspace/activities")
@RequiredArgsConstructor
public class ArtisticEventWorkspaceController {

    private final ArtisticEventLookupFacade lookup;

    @GetMapping("/event-levels")
    public ResponseEntity<Map<String, ArtisticEventLookupFacade.EventLevel>> levels(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(lookup.levelsFor(user.getEmail()));
    }
}
