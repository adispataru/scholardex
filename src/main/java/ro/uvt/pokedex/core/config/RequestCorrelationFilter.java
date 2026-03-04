package ro.uvt.pokedex.core.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.util.UUID;
import java.util.regex.Pattern;

public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String previousRequestId = MDC.get("requestId");
        String previousRoute = MDC.get("route");
        String previousUserId = MDC.get("userId");

        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        String route = request.getRequestURI();
        String userId = resolveUserId(request);

        MDC.put("requestId", requestId);
        MDC.put("route", route);
        MDC.put("userId", userId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            restoreOrRemove("requestId", previousRequestId);
            restoreOrRemove("route", previousRoute);
            restoreOrRemove("userId", previousUserId);
        }
    }

    private String resolveRequestId(String incoming) {
        if (incoming == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = incoming.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REQUEST_ID_LENGTH || !REQUEST_ID_PATTERN.matcher(trimmed).matches()) {
            return UUID.randomUUID().toString();
        }
        return trimmed;
    }

    private String resolveUserId(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "anonymous";
        }
        Object principalObj = authentication.getPrincipal();
        if (principalObj instanceof UserDetails details && details.getUsername() != null && !details.getUsername().isBlank()) {
            return details.getUsername();
        }
        String name = authentication.getName();
        return (name == null || name.isBlank()) ? "anonymous" : name;
    }

    private void restoreOrRemove(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
