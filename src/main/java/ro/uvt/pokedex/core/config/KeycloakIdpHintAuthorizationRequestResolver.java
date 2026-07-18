package ro.uvt.pokedex.core.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps the default resolver to append {@code kc_idp_hint=<alias>} to Keycloak authorization
 * requests, so the default login lands straight on the brokered IdP (Google Workspace) with no
 * interstitial page — neither ours nor Keycloak's.
 *
 * <p>Break-glass: adding {@code ?direct} to the authorization URL
 * ({@code /oauth2/authorization/keycloak?direct}) suppresses the hint, so Keycloak shows the
 * realm's own login form where the realm-local (TOTP-protected) user can sign in. That URL is
 * deliberately not linked from anywhere.
 */
class KeycloakIdpHintAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String AUTHORIZATION_BASE_URI = "/oauth2/authorization";
    private static final String DIRECT_PARAM = "direct";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final String idpHint;

    KeycloakIdpHintAuthorizationRequestResolver(ClientRegistrationRepository repository, String idpHint) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repository, AUTHORIZATION_BASE_URI);
        this.idpHint = idpHint == null ? "" : idpHint.trim();
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withHint(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withHint(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest withHint(OAuth2AuthorizationRequest resolved, HttpServletRequest request) {
        if (resolved == null || idpHint.isBlank() || request.getParameter(DIRECT_PARAM) != null) {
            return resolved;
        }
        Map<String, Object> additional = new LinkedHashMap<>(resolved.getAdditionalParameters());
        additional.put("kc_idp_hint", idpHint);
        return OAuth2AuthorizationRequest.from(resolved)
                .additionalParameters(additional)
                .build();
    }
}
