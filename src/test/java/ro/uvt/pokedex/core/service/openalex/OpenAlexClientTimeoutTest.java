package ro.uvt.pokedex.core.service.openalex;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** A stalled OpenAlex response must fail fast instead of blocking the single-threaded sync scheduler. */
class OpenAlexClientTimeoutTest {

    @Test
    void aResponseThatNeverArrivesFailsAtTheRequestDeadline() {
        WebClient neverAnswers = WebClient.builder()
                .baseUrl("https://openalex.test")
                .exchangeFunction(request -> Mono.never())
                .build();
        OpenAlexClient client = new OpenAlexClient(neverAnswers,
                new com.fasterxml.jackson.databind.ObjectMapper(), "", 200, 5, 1_000L);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(RuntimeException.class, () -> client.fetchWorksByOrcid("0000-0002-3143-8908")));
    }
}
