package ro.uvt.pokedex.core.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;

import java.io.IOException;
import java.util.Set;

/**
 * Security configuration active only under the {@code agent-dev} Spring profile.
 *
 * <p>Bypasses authentication entirely and injects a synthetic RESEARCHER principal
 * so that agents (Claude Code, Codex, etc.) can start the app and inspect all pages
 * at runtime without needing a real account in the database.
 *
 * <p>Activate with: {@code --spring.profiles.active=agent-dev}
 *
 * <p><strong>Never enable in production.</strong>
 */
@Configuration
@Profile("agent-dev")
public class AgentDevSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentDevSecurityConfig.class);

    static final String AGENT_EMAIL = "agent@dev.local";

    @Bean
    @Order(1)
    public SecurityFilterChain agentDevFilterChain(HttpSecurity http,
                                                   ro.uvt.pokedex.core.repository.UserRepository userRepository) throws Exception {
        log.warn("=================================================================");
        log.warn("  agent-dev profile is ACTIVE — all auth checks are BYPASSED");
        log.warn("  Principal: {} (RESEARCHER + PLATFORM_ADMIN + SUPERVISOR; DB-backed when a user doc exists)", AGENT_EMAIL);
        log.warn("  DO NOT use this profile in production.");
        log.warn("=================================================================");

        http
            .securityMatcher("/**")
            .csrf(AbstractHttpConfigurer::disable)
            .addFilterBefore(new AgentDevAuthFilter(userRepository), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(ahr -> ahr.anyRequest().permitAll());

        return http.build();
    }

    /**
     * Injects an {@link User} principal into the {@link SecurityContextHolder} for every request,
     * so downstream controllers that call {@code authentication.getPrincipal() instanceof User}
     * see a valid researcher user. The principal is loaded fresh from the DB when an
     * {@code agent@dev.local} user doc exists (so wizard-saved profile state — linked authors,
     * affiliations, onboarding — is visible to the workspace exactly as for a real user), and
     * falls back to a synthetic profile-less user on an empty database.
     */
    static class AgentDevAuthFilter extends OncePerRequestFilter {

        private final ro.uvt.pokedex.core.repository.UserRepository userRepository;

        AgentDevAuthFilter(ro.uvt.pokedex.core.repository.UserRepository userRepository) {
            this.userRepository = userRepository;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                User agentUser = userRepository.findById(AGENT_EMAIL).orElseGet(() -> {
                    User synthetic = new User();
                    synthetic.setEmail(AGENT_EMAIL);
                    synthetic.setPassword("");
                    synthetic.setLocked(false);
                    return synthetic;
                });
                agentUser.setRoles(Set.of(UserRole.RESEARCHER, UserRole.PLATFORM_ADMIN, UserRole.SUPERVISOR));

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                agentUser, null, agentUser.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }
    }
}
