package ro.uvt.pokedex.core.controller;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(CustomErrorController.class)
@AutoConfigureMockMvc(addFilters = false)
class CustomErrorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void errorRouteMaps404ToNotFoundTemplate() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isOk())
                .andExpect(view().name("errors/error-404"))
                .andExpect(model().attribute("errorStatus", "404"))
                .andExpect(model().attribute("errorTitle", "Page not found"))
                .andExpect(model().attribute("errorShowBrowseLinks", true))
                .andExpect(content().string(containsString("Try one of these areas instead")))
                .andExpect(content().string(not(containsString("stackpath.bootstrapcdn.com/bootstrap"))));
    }

    @Test
    void errorRouteMaps500ToServerErrorTemplate() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
                .andExpect(status().isOk())
                .andExpect(view().name("errors/error-500"))
                .andExpect(model().attribute("errorStatus", "500"))
                .andExpect(model().attribute("errorShowRetry", true))
                .andExpect(model().attributeExists("errorTimestamp"))
                .andExpect(content().string(containsString("Try again")));
    }

    @Test
    void errorRouteMaps403ToForbiddenTemplate() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403))
                .andExpect(status().isOk())
                .andExpect(view().name("errors/error-403"))
                .andExpect(model().attribute("errorStatus", "403"))
                .andExpect(model().attribute("errorTitle", "Access denied"))
                .andExpect(content().string(containsString("ask a platform administrator")));
    }

    @Test
    void errorRouteWithoutStatusMapsToGenericTemplate() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(status().isOk())
                .andExpect(view().name("errors/error"))
                .andExpect(model().attribute("errorStatus", "500"));
    }

    @Test
    void authenticatedErrorRendersInsideAppShell() throws Exception {
        User admin = validPlatformAdmin("admin@uvt.ro", "secret");
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities());

        try {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            mockMvc.perform(get("/error")
                            .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("app-shell-sidebar")))
                    .andExpect(content().string(containsString("Go to admin dashboard")));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void unauthenticatedErrorRendersStandaloneSurface() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app-error-standalone")))
                .andExpect(content().string(not(containsString("app-shell-logout"))));
    }

    private User validPlatformAdmin(String email, String rawPassword) {
        User user = new User();
        user.setEmail(email);
        user.setPassword(new BCryptPasswordEncoder().encode(rawPassword));
        user.setRoles(Set.of(UserRole.PLATFORM_ADMIN));
        return user;
    }
}
