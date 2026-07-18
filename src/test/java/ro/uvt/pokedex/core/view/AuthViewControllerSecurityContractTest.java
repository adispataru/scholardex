package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.savedrequest.SimpleSavedRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

/**
 * OIDC-only authentication contract (H84 purist model): there is NO local password login — every
 * browser flow enters the Keycloak handshake, and the login page exists only for error/logout
 * display plus a manual entry link. The mocked {@link ClientRegistrationRepository} makes the
 * OAuth2 client "configured", so the entry point behaves as in production.
 */
@WebMvcTest(AuthViewController.class)
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class AuthViewControllerSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    /** Suppresses Boot's auto-configured in-memory default user in the slice. */
    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    /** Presence switches the entry point to the OIDC authorization URL (the production shape). */
    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    void loginPageIsAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(view().name("login"));
    }

    @Test
    void loginPageHasNoLocalPasswordForm() throws Exception {
        // OIDC-only: a password field on this page would advertise an auth path that no longer exists.
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"password\""))))
                .andExpect(content().string(not(containsString("name=\"username\""))));
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
    void postLoginIsNotAnAuthenticationEndpointAnymore() throws Exception {
        // Regression guard for the purist cutover: credentials POSTed to /login must never
        // authenticate anyone. With form login disabled there is no handler behind it.
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "admin@uvt.ro")
                        .param("password", "s3cr3t"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void protectedPageRedirectsIntoTheOidcHandshake() throws Exception {
        // No interstitial: unauthenticated browser requests go straight to the authorization endpoint.
        mockMvc.perform(get("/user/workspace"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/keycloak"));
    }

    @Test
    void gateCtaRedirectIsStoredAsTheSavedRequestForTheOidcSuccessHandler() throws Exception {
        // Public-page sign-in CTAs pass ?redirect=<target>; the login page stores it as the Spring
        // Security saved request, which the Keycloak success handler (SavedRequestAware) honors.
        MvcResult page = mockMvc.perform(get("/login").param("redirect", "/forums/sfor_abc123"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) page.getRequest().getSession(false);
        Object saved = session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        org.junit.jupiter.api.Assertions.assertInstanceOf(SimpleSavedRequest.class, saved);
        org.junit.jupiter.api.Assertions.assertEquals("/forums/sfor_abc123",
                ((SimpleSavedRequest) saved).getRedirectUrl());
    }

    @Test
    void unsafeRedirectTargetsAreNeverStored() throws Exception {
        for (String unsafe : new String[] {"https://evil.example", "//evil.example", "/ok\\evil", "javascript:alert(1)"}) {
            MvcResult page = mockMvc.perform(get("/login").param("redirect", unsafe))
                    .andExpect(status().isOk())
                    .andReturn();
            MockHttpSession session = (MockHttpSession) page.getRequest().getSession(false);
            Object saved = session == null ? null : session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
            org.junit.jupiter.api.Assertions.assertNull(saved, "unsafe target must not be stored: " + unsafe);
        }
    }

    @Test
    void logoutRedirectsToLoginLogout() throws Exception {
        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void logoutWorksForBridgedKeycloakLocalUserPrincipal() throws Exception {
        User bridgedUser = platformAdmin("keycloak.admin@uvt.ro");
        UsernamePasswordAuthenticationToken auth =
                UsernamePasswordAuthenticationToken.authenticated(bridgedUser, null, bridgedUser.getAuthorities());

        mockMvc.perform(post("/logout")
                        .with(authentication(auth))
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

    private User platformAdmin(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPassword("{noop}unused");
        user.setRoles(Set.of(UserRole.PLATFORM_ADMIN));
        return user;
    }
}
