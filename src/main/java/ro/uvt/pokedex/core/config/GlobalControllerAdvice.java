package ro.uvt.pokedex.core.config;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ro.uvt.pokedex.core.model.user.User;

import java.util.Optional;

@ControllerAdvice
public class GlobalControllerAdvice {

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

    @ModelAttribute("sidebarContext")
    public String sidebarContext(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return "user";
        }
        if (path.startsWith("/admin/")) {
            return "admin";
        }
        if (path.equals("/admin")) {
            return "admin";
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
                || path.startsWith("/events");
    }
}
