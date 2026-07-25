package ro.uvt.pokedex.core.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import ro.uvt.pokedex.core.model.user.User;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class ErrorPageModelFactory {

    /**
     * H87 S2a: error copy lives in the message bundles, not in this factory — the error pages are the most
     * likely place an anonymous visitor lands, so they must speak the resolved UI language. The locale comes
     * from LocaleContextHolder, which the LocaleResolver has already populated for the request.
     */
    private final org.springframework.context.MessageSource messageSource;

    public ErrorPageModelFactory(org.springframework.context.MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null,
                org.springframework.context.i18n.LocaleContextHolder.getLocale());
    }

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    public void apply(Model model, HttpServletRequest request, int statusCode) {
        ErrorContent content = contentFor(statusCode);
        String path = requestedPath(request);
        Optional<User> currentUser = currentUser(request);
        boolean adminContext = path.startsWith("/admin") || currentUser
                .filter(user -> user.hasRole("PLATFORM_ADMIN"))
                .isPresent();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("user", currentUser.orElseGet(User::new));
        model.addAttribute("errorStatus", String.valueOf(statusCode));
        model.addAttribute("errorTitle", content.title());
        model.addAttribute("errorLead", content.lead());
        model.addAttribute("errorMessage", content.message());
        model.addAttribute("errorIcon", content.icon());
        model.addAttribute("errorTone", content.tone());
        model.addAttribute("errorPath", path);
        model.addAttribute("requestUri", path);
        model.addAttribute("errorTimestamp", TIMESTAMP_FORMAT.format(Instant.now()));
        model.addAttribute("errorRequestId", requestId());
        model.addAttribute("errorShowRetry", statusCode >= 500);
        model.addAttribute("errorShowBrowseLinks", statusCode == 404);
        model.addAttribute("errorActiveSection", activeSection(statusCode, adminContext));
        model.addAttribute("errorPrimaryHref", primaryHref(statusCode, currentUser, adminContext));
        model.addAttribute("errorPrimaryLabel", primaryLabel(statusCode, currentUser, adminContext));
    }

    private ErrorContent contentFor(int statusCode) {
        String prefix = switch (statusCode) {
            case 400, 403, 404, 500 -> "error." + statusCode;
            default -> "error.default";
        };
        return switch (statusCode) {
            case 400 -> content(prefix, "fa-solid fa-triangle-exclamation", "warning");
            case 403 -> content(prefix, "fa-solid fa-lock", "warning");
            case 404 -> content(prefix, "fa-solid fa-magnifying-glass", "primary");
            case 500 -> content(prefix, "fa-solid fa-screwdriver-wrench", "danger");
            default -> content(prefix, "fa-solid fa-circle-exclamation", "danger");
        };
    }

    private ErrorContent content(String prefix, String icon, String tone) {
        return new ErrorContent(msg(prefix + ".title"), msg(prefix + ".lead"), msg(prefix + ".message"), icon, tone);
    }

    private String requestedPath(HttpServletRequest request) {
        Object errorPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (errorPath instanceof String path && !path.isBlank()) {
            return path;
        }
        String uri = request.getRequestURI();
        return (uri == null || uri.isBlank()) ? "/" : uri;
    }

    private Optional<User> currentUser(HttpServletRequest request) {
        if (request.getUserPrincipal() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof User user) {
            return Optional.of(user);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    private String requestId() {
        String requestId = MDC.get("requestId");
        return (requestId == null || requestId.isBlank()) ? null : requestId;
    }

    private String activeSection(int statusCode, boolean adminContext) {
        if (adminContext) {
            return "dashboard";
        }
        return statusCode == 404 ? "scholardex-forums" : "workspace";
    }

    private String primaryHref(int statusCode, Optional<User> currentUser, boolean adminContext) {
        if (currentUser.isEmpty()) {
            return "/login";
        }
        if (statusCode == 404) {
            return "/forums";
        }
        return adminContext ? "/admin" : "/user/workspace";
    }

    private String primaryLabel(int statusCode, Optional<User> currentUser, boolean adminContext) {
        if (currentUser.isEmpty()) {
            return msg("error.action.signIn");
        }
        if (statusCode == 404) {
            return msg("error.action.browseForums");
        }
        return msg(adminContext ? "error.action.adminDashboard" : "error.action.workspace");
    }

    private record ErrorContent(String title, String lead, String message, String icon, String tone) {
    }
}
