package ro.uvt.pokedex.core.config;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.security.ArtisticEventAccessService;

import java.util.Optional;

@ControllerAdvice
public class GlobalControllerAdvice {

    /** Optional, so that web-slice tests need no mock for it (H142 slice 3). */
    private final ObjectProvider<ArtisticEventAccessService> artisticEventAccess;

    public GlobalControllerAdvice(ObjectProvider<ArtisticEventAccessService> artisticEventAccess) {
        this.artisticEventAccess = artisticEventAccess;
    }

    /**
     * H142 slice 3 — whether the sidebar offers the page of artistic events to rank: admins, named experts, heads of the
     * departments that answer for a domain. Asked for page loads only, not for API calls or form posts.
     */
    @ModelAttribute("canRankArtisticEvents")
    public boolean canRankArtisticEvents(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!"GET".equalsIgnoreCase(request.getMethod()) || path == null || path.startsWith("/api/")) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User)) {
            return false;
        }
        ArtisticEventAccessService access = artisticEventAccess.getIfAvailable();
        try {
            return access != null && access.canReviewAny(authentication);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @ModelAttribute("currentUser")
    public Optional<User> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User) {
            return Optional.of((User) authentication.getPrincipal());
        }
        return Optional.empty();
    }

    @ModelAttribute("user")
    public User legacyUserModel() {
        return currentUser().orElseGet(User::new);
    }

    @ModelAttribute("requestUri")
    public String requestUri(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null ? "" : path;
    }

    @ModelAttribute("sidebarContext")
    public String sidebarContext(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return "user";
        }
        if (path.startsWith("/admin/") || path.equals("/admin")) {
            // Heads reach a few pages under /admin (the reports of their faculty or department, their
            // groups). The admin sidebar is a list of pages they cannot open, so they keep the user one.
            return currentUser()
                    .filter(user -> !user.hasRole("PLATFORM_ADMIN"))
                    .map(user -> "user")
                    .orElse("admin");
        }
        if (path.startsWith("/user/")) {
            return "user";
        }
        if (path.equals("/user")) {
            return "user";
        }
        if (isSharedRoute(path)) {
            return currentUser()
                    .filter(user -> user.hasRole("PLATFORM_ADMIN"))
                    .map(user -> "admin")
                    .orElse("user");
        }
        return "user";
    }

    private boolean isSharedRoute(String path) {
        return path.startsWith("/forums")
                || path.startsWith("/wos/")
                || path.equals("/wos")
                || path.startsWith("/core/")
                || path.equals("/core")
                || path.startsWith("/universities")
                || path.startsWith("/events")
                // Delegated researcher-report viewing is reachable by admins (admin sidebar) and
                // supervisors (user sidebar); resolving to admin context for admins keeps them in
                // the admin shell, while supervisors fall through to the user shell where the
                // role-gated "Researcher reports" entry also appears.
                || path.startsWith("/reports/");
    }
}
