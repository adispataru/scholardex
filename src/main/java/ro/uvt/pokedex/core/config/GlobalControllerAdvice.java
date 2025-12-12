package ro.uvt.pokedex.core.config;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ro.uvt.pokedex.core.model.user.User;

@ControllerAdvice
public class GlobalControllerAdvice {

    @ModelAttribute("user")
    public User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User) {
            return (User) authentication.getPrincipal();
        }
        return null; // or a default MyUserDetails instance
    }
}

