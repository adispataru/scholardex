package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.KeycloakOAuth2LoginFailureHandler;
import ro.uvt.pokedex.core.config.KeycloakOAuth2LoginSuccessHandler;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthViewController.class)
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class AuthViewControllerOAuth2SecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockitoBean
    private KeycloakOAuth2LoginSuccessHandler keycloakOAuth2LoginSuccessHandler;

    @MockitoBean
    private KeycloakOAuth2LoginFailureHandler keycloakOAuth2LoginFailureHandler;

    @Test
    void keycloakAuthorizationEndpointRedirectsToProviderWhenClientConfigured() throws Exception {
        when(clientRegistrationRepository.findByRegistrationId("keycloak"))
                .thenReturn(keycloakRegistration());

        mockMvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://keycloak.example/realms/scholardex/protocol/openid-connect/auth")));
    }

    @Test
    void keycloakCallbackPathReachesOAuth2FailureHandlingWhenAuthorizationRequestIsMissing() throws Exception {
        when(clientRegistrationRepository.findByRegistrationId("keycloak"))
                .thenReturn(keycloakRegistration());

        mockMvc.perform(get("/login/oauth2/code/keycloak")
                        .param("code", "abc")
                        .param("state", "missing-state"))
                .andExpect(status().isOk());

        verify(keycloakOAuth2LoginFailureHandler).onAuthenticationFailure(any(), any(), any(AuthenticationException.class));
    }

    private ClientRegistration keycloakRegistration() {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId("scholardex")
                .clientSecret("secret")
                .clientName("Keycloak")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri("https://keycloak.example/realms/scholardex/protocol/openid-connect/auth")
                .tokenUri("https://keycloak.example/realms/scholardex/protocol/openid-connect/token")
                .jwkSetUri("https://keycloak.example/realms/scholardex/protocol/openid-connect/certs")
                .userInfoUri("https://keycloak.example/realms/scholardex/protocol/openid-connect/userinfo")
                .userNameAttributeName("sub")
                .build();
    }
}
