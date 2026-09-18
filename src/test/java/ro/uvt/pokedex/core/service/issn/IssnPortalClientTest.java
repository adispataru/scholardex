package ro.uvt.pokedex.core.service.issn;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssnPortalClientTest {

    private static IssnPortalClient client(Mono<ClientResponse> response, boolean enabled) {
        WebClient webClient = WebClient.builder().baseUrl("https://portal.test")
                .exchangeFunction(request -> response).build();
        return new IssnPortalClient(webClient, 1_000L, enabled);
    }

    private static Mono<ClientResponse> page(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status).header("Content-Type", "text/html; charset=utf-8").body(body).build());
    }

    @Test
    void aRealIssnAnswersWithItsOfficialTitleUnescaped() {
        IssnPortalClient.Lookup lookup = client(page(HttpStatus.OK,
                "<html><head><title>ISSN 1583-7165 - Anale. Seria Informatică (Universitatea &#34;Tibiscus&#34; Timişoara. Print)</title></head></html>"),
                true).lookup("1583-7165");
        assertTrue(lookup.exists());
        assertEquals("Anale. Seria Informatică (Universitatea \"Tibiscus\" Timişoara. Print)", lookup.keyTitle().orElseThrow());
    }

    @Test
    void unassignedAndBadCheckDigitAreAClearNo() {
        assertFalse(client(page(HttpStatus.NOT_FOUND, "<title>ISSN Portal - Page not found</title>"), true)
                .lookup("9999-9994").exists());
        assertFalse(client(page(HttpStatus.BAD_REQUEST, "bad request"), true).lookup("1234-5678").exists());
    }

    @Test
    void aServerErrorOrAnUnrecognisedPageIsCouldNotAskNeverDoesNotExist() {
        assertThrows(IssnPortalClient.IssnPortalUnavailableException.class,
                () -> client(page(HttpStatus.SERVICE_UNAVAILABLE, "down"), true).lookup("1583-7165"));
        // e.g. a bot wall under HTTP 200: no record title on the page
        assertThrows(IssnPortalClient.IssnPortalUnavailableException.class,
                () -> client(page(HttpStatus.OK, "<title>Making sure you're not a bot!</title>"), true).lookup("1583-7165"));
        assertThrows(IssnPortalClient.IssnPortalUnavailableException.class,
                () -> client(Mono.never(), true).lookup("1583-7165"));
    }

    @Test
    void aDisabledClientNeverTouchesTheNetwork() {
        assertThrows(IssnPortalClient.IssnPortalUnavailableException.class,
                () -> client(Mono.error(new AssertionError("must not be called")), false).lookup("1583-7165"));
    }
}
