package ro.uvt.pokedex.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import ro.uvt.pokedex.core.handlers.ApiAccessDeniedHandler;
import ro.uvt.pokedex.core.handlers.ApiAuthenticationEntryPoint;
import ro.uvt.pokedex.core.handlers.CustomAccessDeniedHandler;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;

import java.util.LinkedHashMap;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {

    private final CustomUserDetailsService userDetailsService;

    public WebSecurityConfig(CustomUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManagerBuilder auth) throws Exception {
//        auth.userDetailsService(userDetailsService)
//                .passwordEncoder(passwordEncoder());

        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(ahr -> {
                    ahr.requestMatchers("/admin/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/api/admin/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/api/export", "/api/export/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/api/scrape", "/api/scrape/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/researcher/**").hasAuthority("RESEARCHER");
                    ahr.requestMatchers("/api/supervisor/**").hasAuthority("SUPERVISOR");
                    ahr.anyRequest().authenticated();
                }).exceptionHandling(e -> e
                        .authenticationEntryPoint(delegatingAuthenticationEntryPoint())
                        .accessDeniedHandler(delegatingAccessDeniedHandler()))
                .formLogin(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
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
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();
        entryPoints.put(new AntPathRequestMatcher("/api/**"), apiAuthenticationEntryPoint());
        DelegatingAuthenticationEntryPoint delegating = new DelegatingAuthenticationEntryPoint(entryPoints);
        delegating.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"));
        return delegating;
    }

    @Bean
    public AccessDeniedHandler delegatingAccessDeniedHandler() {
        LinkedHashMap<RequestMatcher, AccessDeniedHandler> handlers = new LinkedHashMap<>();
        handlers.put(new AntPathRequestMatcher("/api/**"), apiAccessDeniedHandler());
        return new RequestMatcherDelegatingAccessDeniedHandler(handlers, accessDeniedHandler());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
