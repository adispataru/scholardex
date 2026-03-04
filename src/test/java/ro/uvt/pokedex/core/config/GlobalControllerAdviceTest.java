package ro.uvt.pokedex.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ro.uvt.pokedex.core.model.user.User;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalControllerAdviceTest {

    private final GlobalControllerAdvice advice = new GlobalControllerAdvice();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentUserReturnsEmptyOptionalWhenUnauthenticated() {
        assertTrue(advice.currentUser().isEmpty());
    }

    @Test
    void currentUserReturnsOptionalWhenAuthenticatedWithUserPrincipal() {
        User user = new User();
        user.setEmail("user@uvt.ro");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null));

        assertTrue(advice.currentUser().isPresent());
    }
}
