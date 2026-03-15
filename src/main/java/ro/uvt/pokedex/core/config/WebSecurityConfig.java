package ro.uvt.pokedex.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern("/api/**")))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(requestCorrelationFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(ahr -> {
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
                            "/favicon.ico"
                    ).permitAll();
                    ahr.requestMatchers("/actuator/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/forums/**", "/wos/**", "/core/**", "/universities/**", "/events/**").authenticated();
                    ahr.requestMatchers("/admin/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/api/admin/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/api/rankings/core/**", "/api/rankings/urap/**").authenticated();
                    ahr.requestMatchers("/api/scopus/forums/**").authenticated();
                    ahr.requestMatchers("/api/scopus/authors/**", "/api/scopus/affiliations/**").authenticated();
                    ahr.requestMatchers("/api/scrape", "/api/scrape/**").hasAuthority("PLATFORM_ADMIN");
                    ahr.requestMatchers("/researcher/**").hasAuthority("RESEARCHER");
                    ahr.requestMatchers("/api/supervisor/**").hasAuthority("SUPERVISOR");
                    ahr.anyRequest().authenticated();
                }).exceptionHandling(e -> e
                        .authenticationEntryPoint(delegatingAuthenticationEntryPoint())
                        .accessDeniedHandler(delegatingAccessDeniedHandler()))
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
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
        AuthenticationEntryPoint loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login");
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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
