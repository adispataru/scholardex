package ro.uvt.pokedex.core.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import ro.uvt.pokedex.core.repository.UserRepository;

/**
 * H87 slice 1 — locale plumbing. {@code ?lang=en} (or {@code ro}) switches the UI and persists the choice via
 * {@link UserPreferenceLocaleResolver}; everything else resolves to the saved preference, then the cookie,
 * then Romanian.
 */
@Configuration
@RequiredArgsConstructor
public class LocaleConfig implements WebMvcConfigurer {

    private final ObjectProvider<UserRepository> userRepositoryProvider;

    @Bean
    public LocaleResolver localeResolver() {
        return new UserPreferenceLocaleResolver(userRepositoryProvider);
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        // An unknown value must not 500 the page; the resolver ignores unsupported tags anyway.
        interceptor.setIgnoreInvalidLocale(true);
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
