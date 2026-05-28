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
        return switch (statusCode) {
            case 400 -> new ErrorContent(
                    "Request could not be processed",
                    "The request needs a correction before ScholarDex can continue.",
                    "Check the URL or submitted values, then try the action again.",
                    "fa-solid fa-triangle-exclamation",
                    "warning"
            );
            case 403 -> new ErrorContent(
                    "Access denied",
                    "Your account does not have permission to open this page.",
                    "If this is part of your role, ask a platform administrator to review your access. "
                            + "Otherwise, return to a workspace you can use.",
                    "fa-solid fa-lock",
                    "warning"
            );
            case 404 -> new ErrorContent(
                    "Page not found",
                    "The page may have moved, been removed, or the address may be mistyped.",
                    "Try searching from the forum directory or browse the ranking areas below.",
                    "fa-solid fa-magnifying-glass",
                    "primary"
            );
            case 500 -> new ErrorContent(
                    "Something went wrong",
                    "ScholarDex could not complete this request.",
                    "Try again in a moment. If the problem continues, include the timestamp and request id "
                            + "when asking for support.",
                    "fa-solid fa-screwdriver-wrench",
                    "danger"
            );
            default -> new ErrorContent(
                    "Unexpected request error",
                    "ScholarDex could not complete this request.",
                    "Use the recovery actions below to return to a known workspace.",
                    "fa-solid fa-circle-exclamation",
                    "danger"
            );
        };
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
            return "Sign in";
        }
        if (statusCode == 404) {
            return "Browse forums";
        }
        return adminContext ? "Go to admin dashboard" : "Go to workspace";
    }

    private record ErrorContent(String title, String lead, String message, String icon, String tone) {
    }
}
