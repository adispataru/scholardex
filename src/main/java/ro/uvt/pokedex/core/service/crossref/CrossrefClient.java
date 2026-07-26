package ro.uvt.pokedex.core.service.crossref;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

/**
 * H92 — keyless Crossref client, used for one thing: recovering the VOLUME title of a Springer
 * proceedings chapter.
 *
 * <p>A paper published in a Springer series sits on a forum named for the SERIES ("Lecture Notes on Data
 * Engineering and Communications Technologies"), which says nothing about the conference, so it falls to
 * the LNCS C floor. Crossref returns {@code container-title} as an ordered pair for these records —
 * {@code [0]} the series, {@code [1]} the volume — and the volume title names the conference outright:
 *
 * <pre>
 *   10.1007/978-3-032-23335-6_20 -> "Advanced Information Networking and Applications"        (AINA)
 *   10.1007/978-3-032-19105-2_17 -> "Machine Learning and Principles and Practice of ..."     (ECML PKDD)
 *   10.1007/978-3-031-96099-4_3  -> "Complex, Intelligent and Software Intensive Systems"     (CISIS)
 * </pre>
 *
 * <p>Crossref asks for a {@code User-Agent} carrying a contact address; that puts callers in the "polite
 * pool" and is the whole of its access policy — there is no key. Like {@link ro.uvt.pokedex.core.service.dblp.DblpClient}
 * this degrades to an empty result on any error rather than failing the sweep around it.
 */
@Service
public class CrossrefClient {

    private static final Logger log = LoggerFactory.getLogger(CrossrefClient.class);

    private final WebClient crossrefWebClient;
    private final String userAgent;
    private final long minIntervalMs;
    private long lastRequestAt = 0L;

    public CrossrefClient(
            @Qualifier("crossrefWebClient") WebClient crossrefWebClient,
            @Value("${crossref.api.user-agent:scholardex/1.0 (https://github.com/adispataru/scholardex)}") String userAgent,
            @Value("${crossref.api.min-interval-ms:200}") long minIntervalMs) {
        this.crossrefWebClient = crossrefWebClient;
        this.userAgent = userAgent;
        this.minIntervalMs = Math.max(0, minIntervalMs);
    }

    /**
     * The volume title for a DOI — {@code container-title[1]} — or empty when the record has only a single
     * container (an ordinary journal article or a one-off book), or is absent from Crossref entirely.
     *
     * <p>Deliberately returns the SECOND entry only. The first is the series, which we already have on the
     * forum and which is exactly the uninformative name this lookup exists to get past.
     */
    public synchronized Optional<String> volumeTitle(String doi) {
        JsonNode message = fetch(doi);
        if (message == null) {
            return Optional.empty();
        }
        JsonNode containers = message.path("container-title");
        if (!containers.isArray() || containers.size() < 2) {
            return Optional.empty();
        }
        String volume = containers.get(1).asText(null);
        return (volume == null || volume.isBlank()) ? Optional.empty() : Optional.of(volume.trim());
    }

    /** The raw {@code message} node, or null on any failure — callers treat absence as "no evidence". */
    private JsonNode fetch(String doi) {
        String normalized = normalizeDoi(doi);
        if (normalized == null) {
            return null;
        }
        throttle();
        try {
            return crossrefWebClient.get()
                    .uri("/works/{doi}", normalized)
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block()
                    .path("message");
        } catch (Exception e) {
            // 404 is the common, uninteresting case (not every DOI is registered with Crossref).
            log.debug("Crossref lookup failed for {}: {}", normalized, e.toString());
            return null;
        }
    }

    /** Strip any {@code https://doi.org/} prefix and keep the bare {@code 10.x/...} path. */
    static String normalizeDoi(String doi) {
        if (doi == null || doi.isBlank()) {
            return null;
        }
        String trimmed = doi.trim();
        int idx = trimmed.indexOf("10.");
        return idx < 0 ? null : trimmed.substring(idx);
    }

    /** Crossref does not publish a hard rate limit for the polite pool; stay well inside it regardless. */
    private void throttle() {
        long wait = minIntervalMs - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }
}
