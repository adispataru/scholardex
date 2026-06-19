package ro.uvt.pokedex.core.service.dblp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import ro.uvt.pokedex.core.service.dblp.dto.DblpSearchResponse;

import java.util.List;

/**
 * H66B Phase 4b — Java-direct DBLP client (keyless public REST API). Per-paper lookup against
 * {@code /search/publ/api} (by DOI, then title), replacing the whole-dump streaming band-aid. DBLP asks callers to
 * be polite; on any error this returns an empty result rather than failing the surrounding sync.
 */
@Service
public class DblpClient {

    private static final Logger log = LoggerFactory.getLogger(DblpClient.class);

    private final WebClient dblpWebClient;
    private final int maxHits;
    private final long minIntervalMs;
    private long lastRequestAt = 0L;

    public DblpClient(
            @Qualifier("dblpWebClient") WebClient dblpWebClient,
            @Value("${dblp.api.max-hits:10}") int maxHits,
            @Value("${dblp.api.min-interval-ms:1500}") long minIntervalMs) {
        this.dblpWebClient = dblpWebClient;
        this.maxHits = Math.max(1, Math.min(30, maxHits));
        this.minIntervalMs = Math.max(0, minIntervalMs);
    }

    /**
     * Search DBLP publications for the given free-text query (a DOI or a title), returning the hit infos in order.
     * DBLP rate-limits aggressive callers (HTTP 429), so requests are throttled to one per {@code min-interval-ms} and
     * a 429 is retried once after the {@code Retry-After} delay; any other error yields an empty result.
     */
    public synchronized List<DblpSearchResponse.DblpInfo> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return doSearch(query.trim(), true);
        } catch (Exception e) {
            log.warn("DBLP search failed for query '{}': {}", query, e.toString());
            return List.of();
        }
    }

    private List<DblpSearchResponse.DblpInfo> doSearch(String query, boolean retryOn429) {
        throttle(minIntervalMs);
        try {
            DblpSearchResponse response = dblpWebClient.get()
                    .uri(builder -> builder.path("/search/publ/api")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("h", maxHits)
                            .build())
                    .retrieve()
                    .bodyToMono(DblpSearchResponse.class)
                    .block();
            if (response == null || response.getResult() == null || response.getResult().getHits() == null
                    || response.getResult().getHits().getHit() == null) {
                return List.of();
            }
            return response.getResult().getHits().getHit().stream()
                    .map(DblpSearchResponse.DblpHit::getInfo)
                    .filter(info -> info != null)
                    .toList();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests e) {
            if (!retryOn429) {
                throw e;
            }
            long retryAfter = e.getHeaders().getFirst("Retry-After") != null
                    ? parseRetryAfterSeconds(e.getHeaders().getFirst("Retry-After")) : 5_000L;
            log.info("DBLP 429 — backing off {} ms then retrying once", retryAfter);
            throttle(retryAfter);
            return doSearch(query, false);
        }
    }

    private void throttle(long intervalMs) {
        long wait = intervalMs - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private static long parseRetryAfterSeconds(String header) {
        try {
            return Math.max(1L, Long.parseLong(header.trim())) * 1000L;
        } catch (NumberFormatException nfe) {
            return 5_000L;
        }
    }

    /**
     * Derive the conference-series stream key from a DBLP record key — the stable conference identity. A DBLP key is
     * {@code <type>/<stream>/<record>} (e.g. {@code conf/iccs/SmithJ19} → {@code conf/iccs}). Returns null for keys
     * that are not conference records ({@code conf/…}) so journals/books never mint a conference forum here.
     */
    public static String conferenceStreamKey(String dblpKey) {
        if (dblpKey == null) {
            return null;
        }
        String[] parts = dblpKey.trim().split("/");
        if (parts.length < 3 || !"conf".equals(parts[0])) {
            return null;
        }
        return parts[0] + "/" + parts[1];
    }
}
