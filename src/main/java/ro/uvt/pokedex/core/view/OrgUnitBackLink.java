package ro.uvt.pokedex.core.view;

import org.springframework.security.core.Authentication;

/**
 * Where "back" leads from the report pages of a faculty or department. Those pages are reached by platform
 * admins from the admin lists and by heads from the supervisor cockpit; the admin lists are closed to heads,
 * so sending a head there would end on an access-denied page.
 */
final class OrgUnitBackLink {

    static final String SUPERVISOR_COCKPIT = "/supervisor";

    private OrgUnitBackLink() {
    }

    static String forPrincipal(Authentication authentication, String adminPage) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> "PLATFORM_ADMIN".equals(granted.getAuthority()));
        return admin ? adminPage : SUPERVISOR_COCKPIT;
    }
}
