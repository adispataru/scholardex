package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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

    @MockitoBean
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
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("autocomplete=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("autocomplete=\"current-password\"")));
    }

    @Test
    void loginPageContainsInstitutionalSignInLink() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/oauth2/authorization/keycloak")))
                .andExpect(content().string(containsString("Continue with institutional account")));
    }

    @Test
    void loginPageUsesBundledAssetsInsteadOfBootstrapCdn() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/assets/app.css")))
                .andExpect(content().string(containsString("/assets/app.js")))
                .andExpect(content().string(not(containsString("stackpath.bootstrapcdn.com/bootstrap"))));
    }

    @Test
    void validCredentialsAuthenticateAndRedirect() throws Exception {
        when(userDetailsService.loadUserByUsername("admin@uvt.ro"))
                .thenReturn(validPlatformAdmin("admin@uvt.ro", "secret"));

        mockMvc.perform(post("/login")
                        .with(csrf())
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
                        .with(csrf())
                        .param("username", "admin@uvt.ro")
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void logoutRedirectsToLoginLogout() throws Exception {
        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void logoutInvalidatesLocalFormLoginSession() throws Exception {
        when(userDetailsService.loadUserByUsername("admin@uvt.ro"))
                .thenReturn(validPlatformAdmin("admin@uvt.ro", "secret"));

        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "admin@uvt.ro")
                        .param("password", "secret"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        mockMvc.perform(post("/logout")
                        .session((MockHttpSession) login.getRequest().getSession(false))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void logoutWorksForBridgedKeycloakLocalUserPrincipal() throws Exception {
        User bridgedUser = validPlatformAdmin("keycloak.admin@uvt.ro", "unused");
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(bridgedUser, null, bridgedUser.getAuthorities());

        mockMvc.perform(post("/logout")
                        .with(authentication(authentication))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void getLogoutIsNotSupportedAsLogoutAction() throws Exception {
        mockMvc.perform(get("/logout")
                        .with(user("admin@uvt.ro")))
                .andExpect(status().isNotFound());
    }

    private User validPlatformAdmin(String email, String rawPassword) {
        User user = new User();
        user.setEmail(email);
        user.setPassword(new BCryptPasswordEncoder().encode(rawPassword));
        user.setRoles(Set.of(UserRole.PLATFORM_ADMIN));
        return user;
    }
}
