package ro.uvt.pokedex.core.view;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.savedrequest.SimpleSavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthViewController {

    /**
     * Session attribute read by Spring Security's {@code HttpSessionRequestCache}, which both the form-login
     * and the Keycloak OAuth2 success handlers consult (they are SavedRequest-aware) to pick the post-login
     * destination.
     */
    static final String SAVED_REQUEST_ATTRIBUTE = "SPRING_SECURITY_SAVED_REQUEST";

    /**
     * Renders the login page. A sign-in CTA on a public page (e.g. the WoS gate on a forum) passes
     * {@code redirect} so the visitor returns to where they were instead of the landing page: a safe
     * internal path is stored as the Spring Security saved request; anything else (absolute URLs,
     * protocol-relative, control characters) is ignored and any stale saved target is cleared so a plain
     * {@code /login} visit always lands on the default page.
     */
    @GetMapping("/login")
    public String login(@RequestParam(name = "redirect", required = false) String redirect, HttpServletRequest request) {
        if (isSafeInternalPath(redirect)) {
            request.getSession().setAttribute(SAVED_REQUEST_ATTRIBUTE, new SimpleSavedRequest(redirect));
        } else {
            request.getSession().removeAttribute(SAVED_REQUEST_ATTRIBUTE);
        }
        return "login";
    }

    /** Relative path on this host only — no absolute/protocol-relative URLs (open redirect), no CR/LF/backslash. */
    private boolean isSafeInternalPath(String redirect) {
        if (redirect == null || redirect.isBlank() || !redirect.startsWith("/") || redirect.startsWith("//")) {
            return false;
        }
        for (int i = 0; i < redirect.length(); i++) {
            char c = redirect.charAt(i);
            if (c == '\\' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}
