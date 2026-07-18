package ro.uvt.pokedex.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import ro.uvt.pokedex.core.handlers.ApiAccessDeniedHandler;
import ro.uvt.pokedex.core.handlers.ApiAuthenticationEntryPoint;
import ro.uvt.pokedex.core.handlers.CustomAccessDeniedHandler;

import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * OIDC-only authentication (H84 "purist" model, decided with the cluster team 2026-07-18):
 * every login flows through the scholardex Keycloak realm — there is NO local password
 * authentication in the app. Break-glass is a realm-local Keycloak user (with TOTP), reached
 * via the hint-free authorization URL {@code /oauth2/authorization/keycloak?direct}; the
 * default flow appends {@code kc_idp_hint} so users land straight on the brokered IdP
 * (Google Workspace) without seeing an interstitial. Local development uses the agent-dev
 * profile (auth bypass) or the staging realm's registered localhost redirect URIs.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {

    private final Optional<ClientRegistrationRepository> clientRegistrationRepository;
    private final Optional<KeycloakOAuth2LoginSuccessHandler> keycloakOAuth2LoginSuccessHandler;
    private final Optional<KeycloakOAuth2LoginFailureHandler> keycloakOAuth2LoginFailureHandler;
    /** Broker IdP alias appended as kc_idp_hint on the default flow; blank disables the hint. */
    private final String idpHint;

    public WebSecurityConfig(
            Optional<ClientRegistrationRepository> clientRegistrationRepository,
            Optional<KeycloakOAuth2LoginSuccessHandler> keycloakOAuth2LoginSuccessHandler,
            Optional<KeycloakOAuth2LoginFailureHandler> keycloakOAuth2LoginFailureHandler,
            @Value("${scholardex.oauth2.keycloak.idp-hint:google}") String idpHint
    ) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.keycloakOAuth2LoginSuccessHandler = keycloakOAuth2LoginSuccessHandler;
        this.keycloakOAuth2LoginFailureHandler = keycloakOAuth2LoginFailureHandler;
        this.idpHint = idpHint;
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern("/api/**")))
                .addFilterBefore(requestCorrelationFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(ahr -> {
                    ahr.requestMatchers("/api/rankings/wos", "/api/rankings/wos/**").authenticated();
                    ahr.requestMatchers(
                            "/login",
                            "/error",
                            "/custom-error",
                            "/actuator/health",
                            "/actuator/health/liveness",
                            "/actuator/health/readiness",
                            "/css/**",
                            "/js/**",
                            "/images/**",
                            "/webjars/**",
                            "/assets/**",
                            "/favicon.ico",
                            "/.well-known/**",
                            "/oauth2/**",
                            "/login/oauth2/**",
                            "/",
                            "/forums/**",
                            "/core/**",
                            "/universities/**",
                            "/events/**",
                            "/rankings",
                            "/publications/**",
                            "/authors",
                            "/authors/**",
                            "/api/entities/authors",
                            "/api/entities/authors/**",
                            "/api/rankings/**"
                    ).permitAll();
                    ahr.requestMatchers("/actuator/**").hasAuthority("PLATFORM_ADMIN");
                    // Groups admin is reachable by supervisors too; per-handler @PreAuthorize enforces ownership.
                    ahr.requestMatchers("/admin/groups/**").hasAnyAuthority("PLATFORM_ADMIN", "SUPERVISOR");
                    ahr.requestMatchers("/admin/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/api/admin/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/api/entities/forums/**").authenticated();
                    ahr.requestMatchers("/api/entities/affiliations/**").authenticated();
                    ahr.requestMatchers("/researcher/**").hasAuthority("RESEARCHER");
                    // Delegated researcher-report viewing: reachable by admins and supervisors at the
                    // URL tier; per-handler @PreAuthorize("@researcherAccess.canView(...)") enforces
                    // which specific researcher a supervisor may see.
                    ahr.requestMatchers("/reports/researcher/**").hasAnyAuthority("PLATFORM_ADMIN", "SUPERVISOR");
                    ahr.requestMatchers("/supervisor/**").hasAnyAuthority("SUPERVISOR", "PLATFORM_ADMIN");
                    ahr.requestMatchers("/api/supervisor/**").hasAuthority("SUPERVISOR");
                    ahr.anyRequest().authenticated();
                }).exceptionHandling(e -> e
                        .authenticationEntryPoint(delegatingAuthenticationEntryPoint())
                        .accessDeniedHandler(delegatingAccessDeniedHandler()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        clientRegistrationRepository.ifPresent(repository ->
                http.oauth2Login(oauth2 -> {
                    oauth2.loginPage("/login")
                            .failureUrl("/login?error");
                    oauth2.authorizationEndpoint(endpoint ->
                            endpoint.authorizationRequestResolver(authorizationRequestResolver(repository)));
                    keycloakOAuth2LoginSuccessHandler.ifPresent(oauth2::successHandler);
                    keycloakOAuth2LoginFailureHandler.ifPresent(oauth2::failureHandler);
                }));

        return http.build();
    }

    /**
     * Default flow appends {@code kc_idp_hint=<alias>} so Keycloak forwards straight to the
     * brokered IdP; {@code ?direct} on the authorization URL suppresses the hint, landing on the
     * realm's own login form — the break-glass path for the realm-local (TOTP) user.
     */
    private OAuth2AuthorizationRequestResolver authorizationRequestResolver(ClientRegistrationRepository repository) {
        return new KeycloakIdpHintAuthorizationRequestResolver(repository, idpHint);
    }

    @Bean
    public RequestCorrelationFilter requestCorrelationFilter() {
        return new RequestCorrelationFilter();
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return new CustomAccessDeniedHandler();
    }

    @Bean
    public AccessDeniedHandler apiAccessDeniedHandler() {
        return new ApiAccessDeniedHandler();
    }

    @Bean
    public AuthenticationEntryPoint apiAuthenticationEntryPoint() {
        return new ApiAuthenticationEntryPoint();
    }

    @Bean
    public AuthenticationEntryPoint delegatingAuthenticationEntryPoint() {
        AuthenticationEntryPoint apiEntryPoint = apiAuthenticationEntryPoint();
        // Browser flows go straight into the OIDC handshake when Keycloak is configured — no
        // interstitial login page. /login itself stays reachable for error/logout display.
        String loginTarget = clientRegistrationRepository.isPresent()
                ? "/oauth2/authorization/keycloak" : "/login";
        AuthenticationEntryPoint loginEntryPoint = new LoginUrlAuthenticationEntryPoint(loginTarget);
        RequestMatcher apiMatcher = PathPatternRequestMatcher.pathPattern("/api/**");
        return (request, response, authException) -> {
            if (apiMatcher.matches(request)) {
                apiEntryPoint.commence(request, response, authException);
            } else {
                loginEntryPoint.commence(request, response, authException);
            }
        };
    }

    @Bean
    public AccessDeniedHandler delegatingAccessDeniedHandler() {
        LinkedHashMap<RequestMatcher, AccessDeniedHandler> handlers = new LinkedHashMap<>();
        handlers.put(PathPatternRequestMatcher.pathPattern("/api/**"), apiAccessDeniedHandler());
        return new RequestMatcherDelegatingAccessDeniedHandler(handlers, accessDeniedHandler());
    }

    @Bean
    public static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
