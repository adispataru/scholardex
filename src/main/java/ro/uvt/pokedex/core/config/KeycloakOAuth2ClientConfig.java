package ro.uvt.pokedex.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import java.util.Arrays;
import java.util.List;

@Configuration
public class KeycloakOAuth2ClientConfig {

    static final String REGISTRATION_ID = "keycloak";
    private static final String PREFIX = "scholardex.oauth2.keycloak.";

    @Bean
    @Conditional(KeycloakOAuth2ClientConfiguredCondition.class)
    public ClientRegistrationRepository keycloakClientRegistrationRepository(
            org.springframework.core.env.Environment environment
    ) {
        String issuerUri = requiredProperty(environment, "issuer-uri");
        String clientId = requiredProperty(environment, "client-id");
        String clientSecret = environment.getProperty(PREFIX + "client-secret", "");
        List<String> scopes = parseScopes(environment.getProperty(PREFIX + "scopes", "openid,profile,email"));

        return new InMemoryClientRegistrationRepository(
                ClientRegistrations.fromIssuerLocation(issuerUri)
                        .registrationId(REGISTRATION_ID)
                        .clientId(clientId)
                        .clientSecret(clientSecret)
                        .scope(scopes)
                        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                        .clientName("Keycloak")
                        .build()
        );
    }

    private static String requiredProperty(org.springframework.core.env.Environment environment, String name) {
        return environment.getRequiredProperty(PREFIX + name).trim();
    }

    private static List<String> parseScopes(String rawScopes) {
        return Arrays.stream(rawScopes.split(","))
                .map(String::trim)
                .filter(scope -> !scope.isBlank())
                .toList();
    }

    static class KeycloakOAuth2ClientConfiguredCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            org.springframework.core.env.Environment environment = context.getEnvironment();
            return hasText(environment.getProperty(PREFIX + "issuer-uri"))
                    && hasText(environment.getProperty(PREFIX + "client-id"));
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
