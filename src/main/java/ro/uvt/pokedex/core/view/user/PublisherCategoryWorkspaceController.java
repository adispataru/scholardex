package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.PublisherCategoryFacade;

import java.util.Map;

/**
 * H143 — the category the platform gives the publisher of each of the researcher's declared books, under every
 * standard that scores it, and the state of their request where no list decides. Read-only: the request itself is
 * made in the record's own fields.
 */
@RestController
@RequestMapping("/user/workspace/activities")
@RequiredArgsConstructor
public class PublisherCategoryWorkspaceController {

    private final PublisherCategoryFacade publisherCategories;

    @GetMapping("/publisher-categories")
    public ResponseEntity<Map<String, PublisherCategoryFacade.RecordView>> publisherCategories(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(publisherCategories.forResearcher(user.getEmail()));
    }
}
