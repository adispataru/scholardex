package ro.uvt.pokedex.core.service.dblp;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ro.uvt.pokedex.core.service.dblp.dto.DblpSearchResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DblpClientTest {

    private static final String HTML_THROTTLE_PAGE =
            "<!DOCTYPE html>\n<html><head><title>dblp: too many requests</title></head><body>slow down</body></html>";
    private static final String JSON_ONE_HIT = """
            {"result":{"status":{"@code":"200"},"hits":{"@total":"1","@sent":"1","hit":[
              {"@score":"5","@id":"1","info":{"key":"conf/ispdc/Filelis-Papadopoulos17","title":"A Paper.",
               "venue":"ISPDC","year":"2017","type":"Conference and Workshop Papers",
               "doi":"10.1109/ISPDC.2017.18","authors":{"author":[{"@pid":"x","text":"A"}]},
               "ee":["https://doi.org/x","https://other"]}}]}}}
            """;
    private static final long BACKOFF_MS = 60_000L;

    /** A stub exchange: each call answers with the next scripted response and counts network round-trips. */
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong nowMillis = new AtomicLong(1_000_000L);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(nowMillis.get()); }
    };

    private DblpClient client(Supplier<Mono<ClientResponse>> responses) {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://dblp.test")
                .exchangeFunction(request -> {
                    requests.incrementAndGet();
                    return responses.get();
                })
                .build();
        // min-interval 0 so tests never sleep; short but valid timeout; 3 consecutive failures open the circuit.
        return new DblpClient(webClient, 10, 0L, 1_000L, BACKOFF_MS, 3, clock);
    }

    private static Mono<ClientResponse> html200() {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=utf-8")
                .body(HTML_THROTTLE_PAGE).build());
    }

    private static Mono<ClientResponse> json200(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body).build());
    }

    @Test
    void parsesJsonBodyIntoHitInfosWithTheJackson2Mapper() {
        DblpClient client = client(() -> json200(JSON_ONE_HIT));

        List<DblpSearchResponse.DblpInfo> hits = client.search("10.1109/ispdc.2017.18");

        assertEquals(1, hits.size());
        assertEquals("conf/ispdc/Filelis-Papadopoulos17", hits.get(0).getKey());
        assertEquals("ISPDC", hits.get(0).getVenue());
        assertEquals("2017", hits.get(0).getYear());
        assertEquals(1, requests.get());
    }

    @Test
    void jsonWithNoMatchesIsAnEmptyResultNotAFailure() {
        DblpClient client = client(() -> json200("{\"result\":{\"hits\":{\"@total\":\"0\",\"@sent\":\"0\"}}}"));

        assertEquals(List.of(), client.search("nothing here"));
    }

    @Test
    void htmlPageUnderHttp200IsARateLimitSignalThatOpensTheBackoff() {
        // Prod 2026-09-14: dblp.org answered the JSON search API with text/html + HTTP 200 for every query on the
        // sweep; the codec threw UnsupportedMediaTypeException per query and the sweep kept asking.
        DblpClient client = client(DblpClientTest::html200);

        DblpUnavailableException first = assertThrows(DblpUnavailableException.class, () -> client.search("q1"));
        assertTrue(first.getMessage().contains("HTML page"), first.getMessage());
        assertEquals(Instant.ofEpochMilli(nowMillis.get() + BACKOFF_MS), first.getBackoffUntil());

        // While backing off, subsequent queries are refused WITHOUT a network round-trip.
        assertThrows(DblpUnavailableException.class, () -> client.search("q2"));
        assertThrows(DblpUnavailableException.class, () -> client.search("q3"));
        assertEquals(1, requests.get());
    }

    @Test
    void markupBodyWithoutAnHtmlContentTypeIsStillTreatedAsBlocked() {
        DblpClient client = client(() -> json200("<html><body>blocked</body></html>"));

        assertThrows(DblpUnavailableException.class, () -> client.search("q"));
    }

    @Test
    void backoffExpiresAndRepeatedBlocksDoubleTheWindow() {
        AtomicInteger phase = new AtomicInteger();
        DblpClient client = client(() -> phase.get() == 2 ? json200(JSON_ONE_HIT) : html200());

        assertThrows(DblpUnavailableException.class, () -> client.search("q"));    // block #1 → 1x window
        nowMillis.addAndGet(BACKOFF_MS);                                                // window elapsed
        DblpUnavailableException second = assertThrows(DblpUnavailableException.class, () -> client.search("q"));
        assertEquals(Instant.ofEpochMilli(nowMillis.get() + 2 * BACKOFF_MS), second.getBackoffUntil()); // block #2 → 2x
        assertEquals(2, requests.get());

        nowMillis.addAndGet(2 * BACKOFF_MS);
        phase.set(2);
        assertEquals(1, client.search("q").size()); // recovered: network asked again, JSON parsed
        assertEquals(3, requests.get());
    }

    @Test
    void transientFailuresAreReportedPerQueryAndOpenTheBackoffAfterARun() {
        DblpClient client = client(() -> Mono.error(new TimeoutException("recvAddress(..) failed")));

        // Two isolated failures: each is a counted lookup failure, the client keeps asking.
        assertThrows(DblpLookupException.class, () -> client.search("q1"));
        DblpLookupException second = assertThrows(DblpLookupException.class, () -> client.search("q2"));
        assertTrue(!(second instanceof DblpUnavailableException));
        // The third consecutive failure opens the circuit.
        DblpUnavailableException third = assertThrows(DblpUnavailableException.class, () -> client.search("q3"));
        assertTrue(third.getMessage().contains("3 consecutive failures"), third.getMessage());
        assertEquals(3, requests.get());
        assertThrows(DblpUnavailableException.class, () -> client.search("q4"));
        assertEquals(3, requests.get());
    }

    @Test
    void persistent429AfterRetryAfterOpensTheBackoff() {
        DblpClient client = client(() -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1").build()));

        assertThrows(DblpUnavailableException.class, () -> client.search("q"));
        assertEquals(2, requests.get()); // first 429 honoured Retry-After and retried once; the second opened the circuit
    }

    @Test
    void derivesConferenceSeriesStreamFromRecordKey() {
        // conf/<stream>/<record> → conf/<stream>; the year-edition record id is dropped (series is the forum grain).
        assertEquals("conf/ispdc", DblpClient.conferenceStreamKey("conf/ispdc/Filelis-Papadopoulos17"));
        assertEquals("conf/iccs", DblpClient.conferenceStreamKey("conf/iccs/SmithJ19"));
    }

    @Test
    void returnsNullForNonConferenceOrMalformedKeys() {
        assertNull(DblpClient.conferenceStreamKey("journals/tocs/SmithJ19")); // journal, not a conference
        assertNull(DblpClient.conferenceStreamKey("conf/iccs"));               // no record segment
        assertNull(DblpClient.conferenceStreamKey(null));
        assertNull(DblpClient.conferenceStreamKey("books/sp/Knuth73"));
    }
}
