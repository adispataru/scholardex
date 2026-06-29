package ro.uvt.pokedex.core.service.cordis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * H64 slice 4b.3 — fetch a single project from the public CORDIS XML export (no auth). Used by the admin
 * "search CORDIS online" endpoint to pull a project + its per-partner € contributions on demand.
 */
@Service
public class CordisProjectClient {

    private static final Logger log = LoggerFactory.getLogger(CordisProjectClient.class);

    private final WebClient webClient;
    private final long timeoutMs;

    public CordisProjectClient(
            @Value("${core.projects.cordis.base-url:https://cordis.europa.eu}") String baseUrl,
            @Value("${core.projects.cordis.timeout-ms:20000}") long timeoutMs,
            @Value("${core.projects.cordis.max-bytes:16777216}") int maxBytes) {
        // WebClient.builder() (static factory, not an injected Builder bean — this is not a reactive web app).
        // Raise the in-memory codec limit well above WebClient's 256KB default: large CORDIS projects (many
        // partners + long objectives) export 300-400KB+ of XML, which otherwise trips DataBufferLimitException.
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
        this.timeoutMs = timeoutMs;
    }

    /** Fetch + parse the CORDIS project for an EU grant id, or throw if absent/unreachable. */
    public CordisProject fetch(String grantId) {
        String xml = webClient.get()
                .uri(b -> b.path("/project/id/{id}").queryParam("format", "xml").build(grantId))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMillis(timeoutMs));
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("Empty CORDIS response for grant id " + grantId);
        }
        CordisProject project = CordisXmlParser.parse(grantId, xml);
        log.info("CORDIS fetch grantId={} acronym={} orgs={} ecMax={}",
                grantId, project.acronym(), project.organizations().size(), project.ecMaxContribution());
        return project;
    }
}
