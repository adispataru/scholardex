package ro.uvt.pokedex.core.handlers;
import jakarta.servlet.ServletException;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import java.io.IOException;

public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(@NonNull jakarta.servlet.http.HttpServletRequest request, @NonNull jakarta.servlet.http.HttpServletResponse response, @NonNull AccessDeniedException accessDeniedException) throws IOException, ServletException {
        response.sendRedirect("/custom-error?error=403");
    }
}

