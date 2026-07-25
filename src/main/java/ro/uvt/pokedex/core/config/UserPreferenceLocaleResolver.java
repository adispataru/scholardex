package ro.uvt.pokedex.core.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.util.List;
import java.util.Locale;

/**
 * H87 — resolves the UI locale from, in order: the signed-in user's saved preference, the language cookie,
 * then Romanian. Romanian is the default because the platform serves Romanian academics and every message we
 * send them is Romanian; English is the opt-in for external readers.
 *
 * <p>Choosing a language persists it on the user document (so it follows them across devices) AND in a cookie
 * (so anonymous visitors and the pre-login screens keep the choice). Report and indicator names are NOT
 * translated in any locale — they are the wording of OM 3019/2025 and the FV templates.</p>
 */
@RequiredArgsConstructor
public class UserPreferenceLocaleResolver implements LocaleResolver {

    private static final Logger log = LoggerFactory.getLogger(UserPreferenceLocaleResolver.class);

    public static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("ro");
    public static final String COOKIE_NAME = "SCHOLARDEX_LANG";
    private static final List<String> SUPPORTED = List.of("ro", "en");
    private static final int COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365;

    /**
     * ObjectProvider, not a hard dependency: {@code @WebMvcTest} slices load WebMvcConfigurer beans but no
     * repositories, so a direct injection would fail every controller slice test in the project (the
     * conditional-bean lesson). Absent repository → the cookie still carries the choice for that request.
     */
    private final ObjectProvider<UserRepository> userRepositoryProvider;

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        String fromUser = currentUser().map(User::getPreferredLanguage).orElse(null);
        if (isSupported(fromUser)) {
            return Locale.forLanguageTag(fromUser);
        }
        String fromCookie = readCookie(request);
        if (isSupported(fromCookie)) {
            return Locale.forLanguageTag(fromCookie);
        }
        return DEFAULT_LOCALE;
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        String tag = locale == null ? null : locale.getLanguage();
        if (!isSupported(tag)) {
            return; // ignore unsupported values instead of pinning the UI to a locale with no bundle
        }
        if (response != null) {
            Cookie cookie = new Cookie(COOKIE_NAME, tag);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
            cookie.setSecure(request != null && request.isSecure());
            response.addCookie(cookie);
        }
        currentUser().ifPresent(user -> persistPreference(user, tag));
    }

    /** Best-effort persistence: a failing write must not break the page the user just asked to translate. */
    private void persistPreference(User principal, String tag) {
        UserRepository userRepository = userRepositoryProvider.getIfAvailable();
        try {
            principal.setPreferredLanguage(tag); // the in-session principal reflects the choice immediately
            if (userRepository == null) {
                return;
            }
            userRepository.findById(principal.getEmail()).ifPresent(stored -> {
                if (!tag.equals(stored.getPreferredLanguage())) {
                    stored.setPreferredLanguage(tag);
                    userRepository.save(stored);
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Could not persist language preference for {}: {}", principal.getEmail(), ex.getMessage());
        }
    }

    private static java.util.Optional<User> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return java.util.Optional.of(user);
        }
        return java.util.Optional.empty();
    }

    private static String readCookie(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static boolean isSupported(String tag) {
        return tag != null && SUPPORTED.contains(tag);
    }
}
