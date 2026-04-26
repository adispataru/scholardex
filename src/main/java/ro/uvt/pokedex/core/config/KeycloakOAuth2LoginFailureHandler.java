package ro.uvt.pokedex.core.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class KeycloakOAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(KeycloakOAuth2LoginFailureHandler.class);

    private final AuthenticationFailureHandler delegate = new SimpleUrlAuthenticationFailureHandler("/login?error");

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        log.warn("Keycloak OAuth2 login failed before local user bridge: {}", exception.getMessage(), exception);
        delegate.onAuthenticationFailure(request, response, exception);
    }
}
