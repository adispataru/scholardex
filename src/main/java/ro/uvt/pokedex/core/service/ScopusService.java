package ro.uvt.pokedex.core.service;
import org.json.JSONArray;
import org.json.JSONObject;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ScopusService {
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
        System.out.println(body);
        return null;
    }

    public void getCitingWorks(Researcher researcher) {

//        String authToken = obtainAuthToken();  // Obtain the auth token

//        for(Publication p : pubs) {
//            String doi = p.getDoi();
//
//            getCitingWorks(doi);
//
//        }


    }

    public List<Map<String, String>> getCitingWorks(String doi) {
        String authToken = obtainAuthToken();  // Obtain the auth token

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ELS-APIKey", apiKey);
//        headers.set("Authorization", "Bearer " + authToken);  // Use the auth token
        headers.set("Accept", "application/json");

        String url = UriComponentsBuilder.fromHttpUrl(apiUrl)
                .buildAndExpand(doi)
                .toUriString();
//        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiUrl)
//                .queryParam("doi", doi);
//                .queryParam("view", "STANDARD");  // Set other query params as necessary.
//                .queryParam("field", "author,title,coverDate,sourceTitle,doi");

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        return parseCitingWorks(response.getBody());
    }

    private List<Map<String, String>> parseCitingWorks(String jsonResponse) {
        JSONObject json = new JSONObject(jsonResponse);
        JSONArray works = json.getJSONObject("search-results").getJSONArray("entry");
        List<Map<String, String>> detailsList = new ArrayList<>();

//        for (int i = 0; i < works.length(); i++) {
//            JSONObject work = works.getJSONObject(i);
//            Map<String, String> workDetails = new HashMap<>();
//            workDetails.put("Title", work.getString("dc:title"));
//            workDetails.put("Author", work.getJSONArray("dc:creator").join(", ").replaceAll("\"", ""));
//            workDetails.put("Date", work.getString("prism:coverDate"));
//            workDetails.put("Source", work.getString("prism:sourceTitle"));
//            workDetails.put("DOI", work.optString("prism:doi", "No DOI available"));
//
//            detailsList.add(workDetails);
//        }

        return detailsList;
    }
}

