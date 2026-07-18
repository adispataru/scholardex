package ro.uvt.pokedex.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeycloakOAuth2LoginSuccessHandlerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final KeycloakOAuth2LoginSuccessHandler handler =
            new KeycloakOAuth2LoginSuccessHandler(userRepository, passwordEncoder);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void existingLocalUserIsReusedWithRolesAndProfilePreserved() {
        User existing = localUser("researcher@uvt.ro", Set.of(UserRole.SUPERVISOR), false);
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName("Ada");
        existing.setResearcherProfile(profile);
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(existing));

        User resolved = handler.resolveLocalUser(oauth2Authentication("researcher@uvt.ro", true));

        assertThat(resolved).isSameAs(existing);
        assertThat(resolved.getRoles()).containsExactly(UserRole.SUPERVISOR);
        assertThat(resolved.getResearcherProfile()).isSameAs(profile);
        verify(userRepository, never()).save(any());
    }

    @Test
    void unknownVerifiedEmailCreatesResearcherUser() {
        when(userRepository.findById("new.user@uvt.ro")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("encoded-random-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User resolved = handler.resolveLocalUser(oauth2Authentication("new.user@uvt.ro", true));

        assertThat(resolved.getEmail()).isEqualTo("new.user@uvt.ro");
        assertThat(resolved.getPassword()).isEqualTo("encoded-random-password");
        assertThat(resolved.getRoles()).containsExactly(UserRole.RESEARCHER);
        assertThat(resolved.isLocked()).isFalse();
        assertThat(resolved.getResearcherProfile()).isNull();

        ArgumentCaptor<String> generatedPassword = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(generatedPassword.capture());
        assertThat(generatedPassword.getValue()).isNotBlank();
        assertThat(generatedPassword.getValue()).isNotEqualTo("new.user@uvt.ro");
    }

    @Test
    void emailIsNormalizedBeforeLookupAndCreate() {
        when(userRepository.findById("mixed.case@uvt.ro")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("encoded-random-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User resolved = handler.resolveLocalUser(oauth2Authentication("  Mixed.Case@UVT.RO  ", true));

        assertThat(resolved.getEmail()).isEqualTo("mixed.case@uvt.ro");
        verify(userRepository).findById("mixed.case@uvt.ro");
    }

    @Test
    void missingEmailFails() {
        assertThatThrownBy(() -> handler.resolveLocalUser(oauth2Authentication(null, true)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void blankEmailFails() {
        assertThatThrownBy(() -> handler.resolveLocalUser(oauth2Authentication("   ", true)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unverifiedEmailFails() {
        assertThatThrownBy(() -> handler.resolveLocalUser(oauth2Authentication("researcher@uvt.ro", false)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void lockedLocalUserFails() {
        User locked = localUser("locked@uvt.ro", Set.of(UserRole.RESEARCHER), true);
        when(userRepository.findById("locked@uvt.ro")).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> handler.resolveLocalUser(oauth2Authentication("locked@uvt.ro", true)))
                .isInstanceOf(LockedException.class);
    }

    @Test
    void successfulBridgeReplacesPrincipalWithLocalUserAuthentication() throws Exception {
        User existing = localUser("researcher@uvt.ro", Set.of(UserRole.RESEARCHER), false);
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(existing));
        Authentication oauth2Authentication = oauth2Authentication("researcher@uvt.ro", true);
        Object details = new Object();
        ((OAuth2AuthenticationToken) oauth2Authentication).setDetails(details);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, oauth2Authentication);

        Authentication bridged = SecurityContextHolder.getContext().getAuthentication();
        assertThat(bridged).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(bridged.getPrincipal()).isSameAs(existing);
        assertThat(bridged.getAuthorities()).extracting("authority").containsExactly("RESEARCHER");
        assertThat(bridged.getDetails()).isSameAs(details);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void invalidPrincipalRedirectsToLoginError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                oauth2Authentication("researcher@uvt.ro", false)
        );

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void emaillessRealmLocalUserFallsBackToPreferredUsernameForExistingAccounts() {
        // Break-glass path: the realm-local Keycloak user may carry no email — preferred_username
        // resolves it, but ONLY against an existing local account.
        User breakGlass = localUser("breakglass", Set.of(UserRole.PLATFORM_ADMIN), false);
        when(userRepository.findById("breakglass")).thenReturn(Optional.of(breakGlass));

        User resolved = handler.resolveLocalUser(usernameOnlyAuthentication("breakglass"));

        assertThat(resolved).isSameAs(breakGlass);
    }

    @Test
    void preferredUsernameNeverAutoProvisionsAnAccount() {
        // A username claim without an email must not mint accounts — provisioning stays
        // strictly verified-email.
        when(userRepository.findById("stranger")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.resolveLocalUser(usernameOnlyAuthentication("stranger")))
                .isInstanceOf(BadCredentialsException.class);
        verify(userRepository, org.mockito.Mockito.never()).save(any(User.class));
    }

    @Test
    void lockedRealmLocalFallbackAccountFails() {
        User locked = localUser("breakglass", Set.of(UserRole.PLATFORM_ADMIN), true);
        when(userRepository.findById("breakglass")).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> handler.resolveLocalUser(usernameOnlyAuthentication("breakglass")))
                .isInstanceOf(LockedException.class);
    }

    private Authentication usernameOnlyAuthentication(String preferredUsername) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "subject-123");
        attributes.put("preferred_username", preferredUsername);
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OIDC_USER")),
                attributes,
                "sub"
        );
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "keycloak");
    }

    private Authentication oauth2Authentication(String email, Object emailVerified) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "subject-123");
        attributes.put("email", email);
        attributes.put("email_verified", emailVerified);
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OIDC_USER")),
                attributes,
                "sub"
        );
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "keycloak");
    }

    private User localUser(String email, Set<UserRole> roles, boolean locked) {
        User user = new User();
        user.setEmail(email);
        user.setPassword("local-password");
        user.setRoles(roles);
        user.setLocked(locked);
        return user;
    }
}
