package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;

import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AuthViewController.class)
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class AuthViewControllerSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomUserDetailsService userDetailsService;

    @Test
    void loginPageIsAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(view().name("login"));
    }

    @Test
    void loginPageContainsStandardAutocompleteContract() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"username\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("autocomplete=\"username\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"password\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("autocomplete=\"current-password\"")));
    }

    @Test
    void validCredentialsAuthenticateAndRedirect() throws Exception {
        when(userDetailsService.loadUserByUsername("admin@uvt.ro"))
                .thenReturn(validPlatformAdmin("admin@uvt.ro", "secret"));

        mockMvc.perform(post("/login")
                        .param("username", "admin@uvt.ro")
                        .param("password", "secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void invalidCredentialsRedirectToLoginError() throws Exception {
        when(userDetailsService.loadUserByUsername("admin@uvt.ro"))
                .thenReturn(validPlatformAdmin("admin@uvt.ro", "secret"));

        mockMvc.perform(post("/login")
                        .param("username", "admin@uvt.ro")
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void logoutRedirectsToLoginLogout() throws Exception {
        mockMvc.perform(post("/logout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    private User validPlatformAdmin(String email, String rawPassword) {
        User user = new User();
        user.setEmail(email);
        user.setPassword(new BCryptPasswordEncoder().encode(rawPassword));
        user.setRoles(Set.of(UserRole.PLATFORM_ADMIN));
        return user;
    }
}
