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
     * Renders the login page. Two flows feed the post-login destination and both must survive this page:
     * a sign-in CTA on a public page passes {@code redirect} (stored as the Spring Security saved request
     * when it is a safe internal path), while an interrupted request to a protected page (session expiry /
     * app restart → refresh → redirect here) has already been saved by the security filter chain. A plain
     * {@code /login} visit therefore leaves any existing saved request UNTOUCHED — clearing it would send
     * the interrupted visitor to the landing page instead of the page they asked for.
     */
    @GetMapping("/login")
    public String login(@RequestParam(name = "redirect", required = false) String redirect, HttpServletRequest request) {
        if (isSafeInternalPath(redirect)) {
            request.getSession().setAttribute(SAVED_REQUEST_ATTRIBUTE, new SimpleSavedRequest(redirect));
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
