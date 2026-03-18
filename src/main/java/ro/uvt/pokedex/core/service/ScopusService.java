package ro.uvt.pokedex.core.service;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.service.integration.IntegrationErrorCode;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScopusService {
    private static final Logger log = LoggerFactory.getLogger(ScopusService.class);
    private static final Pattern XML_TOKEN_PATTERN = Pattern.compile("<token>(.*?)</token>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String apiUrl;
    private final String authUrl;

    @Autowired
    public ScopusService(RestTemplate restTemplate,
                         @Value("${scopus.api.key}") String apiKey,
                         @Value("${scopus.api.url}") String apiUrl,
                         @Value("${scopus.auth.url}") String authUrl) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.authUrl = authUrl;
    }

    public String obtainAuthToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ELS-APIKey", apiKey);
        headers.set("Accept", "text/xml");

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(authUrl, HttpMethod.GET, entity, String.class);
        return parseToken(response.getBody()); // Implement this method based on the expected JSON or XML response.
    }

    private String parseToken(String body) {
        log.debug("Scopus auth response received: bodyPresent={}, bodyLength={}", body != null, body != null ? body.length() : 0);
        if (body == null || body.isBlank()) {
            throw new IntegrationException(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, false, "Scopus auth response body is empty");
        }
        try {
            JSONObject json = new JSONObject(body);
            String token = json.optString("token", "").trim();
            if (!token.isBlank()) {
                return token;
            }
        } catch (Exception ignored) {
            // Fallback to XML parsing.
        }
        Matcher matcher = XML_TOKEN_PATTERN.matcher(body);
        if (matcher.find()) {
            String token = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (!token.isBlank()) {
                return token;
            }
        }
        throw new IntegrationException(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, false, "Scopus auth token could not be parsed");
    }

    public void getCitingWorks(Researcher researcher) {
    }

    public List<Map<String, String>> getCitingWorks(String doi) {
        String authToken = obtainAuthToken();  // Obtain the auth token

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ELS-APIKey", apiKey);
        headers.set("Accept", "application/json");

        String url = UriComponentsBuilder.fromUriString(apiUrl)
                .buildAndExpand(doi)
                .toUriString();
        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        return parseCitingWorks(response.getBody());
    }

    private List<Map<String, String>> parseCitingWorks(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isBlank()) {
            return List.of();
        }
        JSONObject json = new JSONObject(jsonResponse);
        JSONObject searchResults = json.optJSONObject("search-results");
        if (searchResults == null) {
            return List.of();
        }
        JSONArray works = searchResults.optJSONArray("entry");
        if (works == null) {
            return List.of();
        }
        List<Map<String, String>> detailsList = new ArrayList<>();

        return detailsList;
    }
}
