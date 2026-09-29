package ro.uvt.pokedex.core.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.service.application.SupervisorWorkspaceService;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class KeycloakOAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(KeycloakOAuth2LoginSuccessHandler.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SupervisorWorkspaceService supervisorWorkspaceService;
    private final SavedRequestAwareAuthenticationSuccessHandler successHandler;
    private final AuthenticationFailureHandler failureHandler;

    /**
     * H128: the domains whose accounts are created at first sign-in (the same list a head may invite from).
     * The realm restricts sign-in to the university's accounts already; this is the second barrier, inside
     * the application. It gates only the CREATION of an account: whoever already has one — an external
     * candidate an admin created, a realm-local break-glass user — signs in as before.
     */
    @org.springframework.beans.factory.annotation.Value("${app.roster.invite-allowed-domains:e-uvt.ro}")
    private String allowedDomainsCsv = "e-uvt.ro";

    public KeycloakOAuth2LoginSuccessHandler(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                             SupervisorWorkspaceService supervisorWorkspaceService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.supervisorWorkspaceService = supervisorWorkspaceService;
        this.successHandler = new SavedRequestAwareAuthenticationSuccessHandler();
        this.successHandler.setDefaultTargetUrl("/");
        this.failureHandler = new SimpleUrlAuthenticationFailureHandler("/login?error");
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        try {
            User localUser = resolveLocalUser(authentication);
            // A head of a faculty or department, or a group supervisor, signs in as a SUPERVISOR. Derived
            // from the appointment, not stored on the account (see User#supervisorByPosition).
            localUser.setSupervisorByPosition(
                    !supervisorWorkspaceService.buildView(localUser.getEmail()).isEmpty());
            UsernamePasswordAuthenticationToken localAuthentication =
                    UsernamePasswordAuthenticationToken.authenticated(localUser, null, localUser.getAuthorities());
            localAuthentication.setDetails(authentication.getDetails());
            SecurityContextHolder.getContext().setAuthentication(localAuthentication);
            successHandler.onAuthenticationSuccess(request, response, localAuthentication);
        } catch (AuthenticationException ex) {
            log.warn("Keycloak OAuth2 login rejected: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
            failureHandler.onAuthenticationFailure(request, response, ex);
        }
    }

    User resolveLocalUser(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof OAuth2User oauth2User)) {
            throw new BadCredentialsException("OAuth2 principal is required");
        }

        String email = normalizeEmail(oauth2User.getAttribute("email"));
        Object emailVerified = oauth2User.getAttribute("email_verified");
        if (email.isBlank()) {
            // Break-glass path: a realm-local Keycloak user may carry no email at all — fall back
            // to preferred_username, but ONLY against an EXISTING local account (auto-provisioning
            // stays strictly verified-email, so a username claim can never mint an account).
            String username = normalizeEmail(oauth2User.getAttribute("preferred_username"));
            if (!username.isBlank()) {
                User existing = userRepository.findById(username).orElse(null);
                if (existing != null) {
                    if (existing.isLocked()) {
                        throw new LockedException("User account is locked");
                    }
                    log.info("OAuth2 login via preferred_username fallback (realm-local user): {}", username);
                    return existing;
                }
            }
            log.warn("Keycloak OAuth2 principal did not include a usable email claim. Available claim keys: {}",
                    oauth2User.getAttributes().keySet());
            throw new BadCredentialsException("Verified email is required");
        }
        if (!isEmailVerified(emailVerified)) {
            log.warn("Keycloak OAuth2 principal for {} was rejected because email_verified was {}",
                    email, emailVerified);
            throw new BadCredentialsException("Verified email is required");
        }

        User user = userRepository.findById(email).orElse(null);
        if (user == null) {
            if (!isAllowedDomain(email)) {
                log.warn("Keycloak OAuth2 principal {} has no account and its domain is not one the platform"
                        + " creates accounts for", email);
                throw new BadCredentialsException("No account for this email");
            }
            user = createResearcherUser(email);
        }
        if (user.isLocked()) {
            log.warn("Keycloak OAuth2 principal for {} matched a locked local user", email);
            throw new LockedException("User account is locked");
        }
        return user;
    }

    private boolean isAllowedDomain(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1);
        return java.util.Arrays.stream(allowedDomainsCsv.split(","))
                .map(d -> d.strip().toLowerCase(java.util.Locale.ROOT))
                .anyMatch(domain::equals);
    }

    private User createResearcherUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setRoles(Set.of(UserRole.RESEARCHER));
        user.setLocked(false);
        return userRepository.save(user);
    }

    private String normalizeEmail(Object email) {
        if (!(email instanceof String value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isEmailVerified(Object emailVerified) {
        return Boolean.TRUE.equals(emailVerified) || "true".equalsIgnoreCase(String.valueOf(emailVerified));
    }
}
