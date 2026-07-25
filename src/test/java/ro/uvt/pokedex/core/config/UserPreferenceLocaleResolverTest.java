package ro.uvt.pokedex.core.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPreferenceLocaleResolverTest {

    @Mock private UserRepository userRepository;

    private UserPreferenceLocaleResolver resolver() {
        return new UserPreferenceLocaleResolver(provider(userRepository));
    }

    /** The production wiring is an ObjectProvider so controller-slice tests can run without repositories. */
    private static ObjectProvider<UserRepository> provider(UserRepository repository) {
        return new ObjectProvider<>() {
            @Override public UserRepository getObject() { return repository; }
            @Override public UserRepository getObject(Object... args) { return repository; }
            @Override public UserRepository getIfAvailable() { return repository; }
            @Override public UserRepository getIfUnique() { return repository; }
        };
    }

    /** No repository bean (the @WebMvcTest slice shape): the choice still lands in the cookie. */
    @Test
    void withoutARepositoryTheChoiceStillWorksViaTheCookie() {
        signIn("u@uvt.ro", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        UserPreferenceLocaleResolver noRepo = new UserPreferenceLocaleResolver(provider(null));

        noRepo.setLocale(new MockHttpServletRequest(), response, Locale.forLanguageTag("en"));

        assertThat(response.getCookie(UserPreferenceLocaleResolver.COOKIE_NAME).getValue()).isEqualTo("en");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static User signIn(String email, String preferredLanguage) {
        User user = new User();
        user.setEmail(email);
        user.setPreferredLanguage(preferredLanguage);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, "RESEARCHER"));
        return user;
    }

    @Test
    void romanianIsTheDefaultForAnonymousVisitors() {
        assertThat(resolver().resolveLocale(new MockHttpServletRequest()).getLanguage()).isEqualTo("ro");
    }

    /** Error pages: no handler matched, so LocaleChangeInterceptor never ran — the switcher must still work. */
    @Test
    void theLangParameterWinsEvenWhenNoInterceptorHasRun() {
        signIn("u@uvt.ro", "ro");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(UserPreferenceLocaleResolver.PARAM_NAME, "en");

        assertThat(resolver().resolveLocale(request).getLanguage()).isEqualTo("en");
    }

    @Test
    void anUnsupportedLangParameterIsIgnoredInFavourOfTheSavedPreference() {
        signIn("u@uvt.ro", "en");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(UserPreferenceLocaleResolver.PARAM_NAME, "fr");

        assertThat(resolver().resolveLocale(request).getLanguage()).isEqualTo("en");
    }

    @Test
    void theSavedUserPreferenceWinsOverTheCookie() {
        signIn("u@uvt.ro", "en");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(UserPreferenceLocaleResolver.COOKIE_NAME, "ro"));

        assertThat(resolver().resolveLocale(request).getLanguage()).isEqualTo("en");
    }

    @Test
    void theCookieIsUsedWhenTheUserHasNeverChosen() {
        signIn("u@uvt.ro", null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(UserPreferenceLocaleResolver.COOKIE_NAME, "en"));

        assertThat(resolver().resolveLocale(request).getLanguage()).isEqualTo("en");
    }

    @Test
    void anUnsupportedLanguageFallsBackToRomanianRatherThanRenderingKeys() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(UserPreferenceLocaleResolver.COOKIE_NAME, "fr"));

        assertThat(resolver().resolveLocale(request).getLanguage()).isEqualTo("ro");
    }

    @Test
    void choosingALanguagePersistsItOnTheUserAndInACookie() {
        User principal = signIn("u@uvt.ro", null);
        User stored = new User();
        stored.setEmail("u@uvt.ro");
        when(userRepository.findById("u@uvt.ro")).thenReturn(Optional.of(stored));
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver().setLocale(new MockHttpServletRequest(), response, Locale.forLanguageTag("en"));

        verify(userRepository).save(stored);
        assertThat(stored.getPreferredLanguage()).isEqualTo("en");
        assertThat(principal.getPreferredLanguage()).as("in-session principal stays consistent").isEqualTo("en");
        Cookie cookie = response.getCookie(UserPreferenceLocaleResolver.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("en");
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    @Test
    void anUnsupportedChoiceIsIgnoredEntirely() {
        signIn("u@uvt.ro", null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver().setLocale(new MockHttpServletRequest(), response, Locale.FRENCH);

        verify(userRepository, never()).save(any());
        assertThat(response.getCookie(UserPreferenceLocaleResolver.COOKIE_NAME)).isNull();
    }

    @Test
    void anonymousChoiceIsKeptInTheCookieWithoutTouchingTheDatabase() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver().setLocale(new MockHttpServletRequest(), response, Locale.forLanguageTag("en"));

        verify(userRepository, never()).save(any());
        assertThat(response.getCookie(UserPreferenceLocaleResolver.COOKIE_NAME).getValue()).isEqualTo("en");
    }
}
