package ro.uvt.pokedex.core.service.dblp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import ro.uvt.pokedex.core.service.dblp.dto.DblpSearchResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * H66B Phase 4b — Java-direct DBLP client (keyless public REST API). Per-paper lookup against
 * {@code /search/publ/api} (by DOI, then title), replacing the whole-dump streaming band-aid. DBLP asks callers to
 * be polite: requests are throttled, and once DBLP signals that it is throttling <em>us</em> the client backs off
 * for a whole window instead of retrying on every next query.
 *
 * <p>Failure contract (a sweep needs to tell "no hits" from "could not ask"):
 * <ul>
 *   <li>a normal JSON reply with no matches → empty list;</li>
 *   <li>a transient failure (timeout, I/O, 5xx, unparseable body) → {@link DblpLookupException};</li>
 *   <li>a rate-limit/block signal → {@link DblpUnavailableException}, and every further call throws the same until
 *       the backoff window has passed, without touching the network.</li>
 * </ul>
 */
@Service
public class DblpClient {

    private static final Logger log = LoggerFactory.getLogger(DblpClient.class);
    /** Static on purpose — a JSON parser, not a bean; same pattern as {@code CrossrefClient}. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** Repeated blocks double the window each time, up to this multiple of the configured base. */
    private static final long BACKOFF_MAX_MULTIPLIER = 8L;

    private final WebClient dblpWebClient;
    private final int maxHits;
    private final long minIntervalMs;
    private final Duration timeout;
    private final long blockBackoffMs;
    private final int maxConsecutiveFailures;
    private final Clock clock;
    private long lastRequestAt = 0L;
    private long backoffUntil = 0L;
    private int consecutiveBlocks = 0;
    private int consecutiveFailures = 0;

    @Autowired
    public DblpClient(
            @Qualifier("dblpWebClient") WebClient dblpWebClient,
            @Value("${dblp.api.max-hits:10}") int maxHits,
            @Value("${dblp.api.min-interval-ms:1500}") long minIntervalMs,
            @Value("${dblp.api.timeout-ms:15000}") long timeoutMs,
            @Value("${dblp.api.block-backoff-ms:300000}") long blockBackoffMs,
            @Value("${dblp.api.max-consecutive-failures:3}") int maxConsecutiveFailures) {
        this(dblpWebClient, maxHits, minIntervalMs, timeoutMs, blockBackoffMs, maxConsecutiveFailures,
                Clock.systemUTC());
    }

    DblpClient(WebClient dblpWebClient, int maxHits, long minIntervalMs, long timeoutMs, long blockBackoffMs,
               int maxConsecutiveFailures, Clock clock) {
        this.dblpWebClient = dblpWebClient;
        this.maxHits = Math.max(1, Math.min(30, maxHits));
        this.minIntervalMs = Math.max(0, minIntervalMs);
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.blockBackoffMs = Math.max(1000, blockBackoffMs);
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        this.clock = clock;
    }

    /**
     * Search DBLP publications for the given free-text query (a DOI or a title), returning the hit infos in order.
     * Requests are throttled to one per {@code min-interval-ms}; a 429 is retried once after the {@code Retry-After}
     * delay. See the class contract for what is thrown when DBLP cannot be asked.
     */
    public synchronized List<DblpSearchResponse.DblpInfo> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (clock.millis() < backoffUntil) {
            throw new DblpUnavailableException(
                    "DBLP unavailable — backing off until " + Instant.ofEpochMilli(backoffUntil),
                    Instant.ofEpochMilli(backoffUntil));
        }
        String q = query.trim();
        try {
            List<DblpSearchResponse.DblpInfo> hits = doSearch(q, true);
            consecutiveFailures = 0;
            consecutiveBlocks = 0;
            return hits;
        } catch (BlockedResponse blocked) {
            throw enterBackoff(q, blocked.getMessage());
        } catch (Exception e) {
            consecutiveFailures++;
            log.warn("DBLP search failed for query '{}' ({} consecutive): {}", q, consecutiveFailures, e.toString());
            if (consecutiveFailures >= maxConsecutiveFailures) {
                throw enterBackoff(q, consecutiveFailures + " consecutive failures, last: " + e);
            }
            throw new DblpLookupException("DBLP lookup failed: " + e, e);
        }
    }

    private List<DblpSearchResponse.DblpInfo> doSearch(String query, boolean retryOn429) {
        throttle(minIntervalMs);
        ResponseEntity<String> entity;
        try {
            entity = dblpWebClient.get()
                    .uri(builder -> builder.path("/search/publ/api")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("h", maxHits)
                            .build())
                    .retrieve()
                    // Decoded as a STRING and parsed with the Jackson 2 mapper (the repo's WebClient pattern):
                    // DBLP answers a throttled caller with an HTML page under HTTP 200, and letting the codec decode
                    // straight into the DTO turned that into an UnsupportedMediaTypeException per query — with
                    // nothing telling the sweep to stop asking.
                    .toEntity(String.class)
                    // A hard deadline: block() without one waits for the OS-level TCP timeout on a dead peer.
                    .timeout(timeout)
                    .block();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests e) {
            if (!retryOn429) {
                throw new BlockedResponse("HTTP 429 persisted after honouring Retry-After");
            }
            long retryAfter = e.getHeaders().getFirst("Retry-After") != null
                    ? parseRetryAfterSeconds(e.getHeaders().getFirst("Retry-After")) : 5_000L;
            log.info("DBLP 429 — backing off {} ms then retrying once", retryAfter);
            throttle(retryAfter);
            return doSearch(query, false);
        }
        if (entity == null || entity.getBody() == null || entity.getBody().isBlank()) {
            return List.of();
        }
        MediaType contentType = entity.getHeaders().getContentType();
        if (isHtml(contentType, entity.getBody())) {
            throw new BlockedResponse("HTML page (content-type " + contentType
                    + ") returned for the JSON search API — rate-limited or blocked");
        }
        DblpSearchResponse response = parse(entity.getBody());
        if (response == null || response.getResult() == null || response.getResult().getHits() == null
                || response.getResult().getHits().getHit() == null) {
            return List.of();
        }
        return response.getResult().getHits().getHit().stream()
                .map(DblpSearchResponse.DblpHit::getInfo)
                .filter(info -> info != null)
                .toList();
    }

    private static DblpSearchResponse parse(String body) {
        try {
            return JSON.readValue(body, DblpSearchResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable DBLP response: " + e.getMessage(), e);
        }
    }

    /** DBLP's throttle page: {@code text/html} under HTTP 200 — or any body that is markup rather than JSON. */
    private static boolean isHtml(MediaType contentType, String body) {
        if (contentType != null && MediaType.TEXT_HTML.isCompatibleWith(contentType)) {
            return true;
        }
        String head = body.stripLeading();
        if (head.startsWith("\uFEFF")) {
            head = head.substring(1).stripLeading();
        }
        return head.startsWith("<");
    }

    private DblpUnavailableException enterBackoff(String query, String reason) {
        consecutiveBlocks++;
        consecutiveFailures = 0;
        long multiplier = Math.min(1L << (consecutiveBlocks - 1), BACKOFF_MAX_MULTIPLIER);
        long windowMs = blockBackoffMs * multiplier;
        backoffUntil = clock.millis() + windowMs;
        Instant until = Instant.ofEpochMilli(backoffUntil);
        log.warn("DBLP unavailable ({}); pausing DBLP lookups for {} ms, until {} — last query '{}'",
                reason, windowMs, until, query);
        return new DblpUnavailableException("DBLP unavailable: " + reason + "; backing off until " + until, until);
    }

    private void throttle(long intervalMs) {
        long wait = intervalMs - (clock.millis() - lastRequestAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = clock.millis();
    }

    private static long parseRetryAfterSeconds(String header) {
        try {
            return Math.max(1L, Long.parseLong(header.trim())) * 1000L;
        } catch (NumberFormatException nfe) {
            return 5_000L;
        }
    }

    /** Internal signal: DBLP is throttling us (raised inside {@link #doSearch}, turned into a backoff by {@link #search}). */
    private static final class BlockedResponse extends RuntimeException {
        BlockedResponse(String message) {
            super(message);
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
