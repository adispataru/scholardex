package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.Optional;
import java.util.Set;

/**
 * H119 — what the public (not signed in) pages may show. The catalogue pages stay public as the university's
 * publication showcase, but a visitor sees only publications and authors of the university's own researchers,
 * and no citation metrics: the corpus also holds the papers that CITE ours, with their authors, and the licence
 * of the sources does not cover showing those, nor aggregated citation numbers, outside the institution.
 * <p>
 * "The university's researchers" is the platform's own notion ({@link CacheService#getUniversityAuthorIds()}:
 * every researcher account that is not an external candidate), the one the CNFIS export counts by.
 */
@Component
@RequiredArgsConstructor
public class PublicCatalogScope {

    private final CacheService cacheService;

    /** Empty for a signed-in user (no restriction); otherwise the author ids a visitor's view is limited to. */
    public Optional<Set<String>> restrictionFor(Authentication authentication) {
        if (isSignedIn(authentication)) {
            return Optional.empty();
        }
        return Optional.of(Set.copyOf(cacheService.getUniversityAuthorIds()));
    }

    public static boolean isSignedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
