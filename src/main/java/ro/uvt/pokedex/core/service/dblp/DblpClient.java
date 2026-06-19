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

    public DblpClient(
            @Qualifier("dblpWebClient") WebClient dblpWebClient,
            @Value("${dblp.api.max-hits:10}") int maxHits) {
        this.dblpWebClient = dblpWebClient;
        this.maxHits = Math.max(1, Math.min(30, maxHits));
    }

    /** Search DBLP publications for the given free-text query (a DOI or a title), returning the hit infos in order. */
    public List<DblpSearchResponse.DblpInfo> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            DblpSearchResponse response = dblpWebClient.get()
                    .uri(builder -> builder.path("/search/publ/api")
                            .queryParam("q", query.trim())
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
        } catch (Exception e) {
            log.warn("DBLP search failed for query '{}': {}", query, e.toString());
            return List.of();
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
