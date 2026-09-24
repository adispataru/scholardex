package ro.uvt.pokedex.core.service.wos;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WosOpenUrlClientTest {

    private final AtomicInteger requests = new AtomicInteger();

    private WosOpenUrlClient client(Mono<ClientResponse> response, boolean enabled) {
        WebClient webClient = WebClient.builder().baseUrl("https://wos.test")
                .exchangeFunction(request -> { requests.incrementAndGet(); return response; }).build();
        return new WosOpenUrlClient(webClient, 1_000L, 0L, enabled);
    }

    private static Mono<ClientResponse> redirect(String location) {
        return Mono.just(ClientResponse.create(HttpStatus.FOUND).header("Location", location).build());
    }

    @Test
    void anIndexedDoiYieldsTheAccessionNumberFromTheRedirect() {
        WosOpenUrlClient.Lookup lookup = client(redirect(
                "https://www.webofscience.com/api/gateway?GWVersion=2&SrcApp=PARTNER_APP&SrcAuth=TROpenURL"
                        + "&KeyUT=WOS:000562375500001&DestLinkType=FullRecord&DestApp=WOS_CPL&UsrCustomerID=x"), true)
                .lookup("10.1016/j.fss.2019.09.014");
        assertEquals(WosOpenUrlClient.Outcome.FOUND, lookup.outcome());
        assertEquals("WOS:000562375500001", lookup.wosId().orElseThrow());
    }

    @Test
    void aDoiWithoutARecordIsAClearNo() {
        WosOpenUrlClient.Lookup lookup = client(redirect("http://ws.isiknowledge.com:80/cps/openurl_web/OpenURLNoRecord.html"), true)
                .lookup("10.5281/zenodo.1234567");
        assertEquals(WosOpenUrlClient.Outcome.NOT_FOUND, lookup.outcome());
        assertTrue(lookup.wosId().isEmpty());
    }

    @Test
    void aServerErrorAnUnexpectedRedirectOrAHangIsUnavailableNeverNo() {
        assertEquals(WosOpenUrlClient.Outcome.UNAVAILABLE,
                client(Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()), true).lookup("10.1/x").outcome());
        assertEquals(WosOpenUrlClient.Outcome.UNAVAILABLE,
                client(redirect("https://www.webofscience.com/somewhere/else"), true).lookup("10.1/x").outcome());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertEquals(WosOpenUrlClient.Outcome.UNAVAILABLE, client(Mono.never(), true).lookup("10.1/x").outcome()));
    }

    @Test
    void aDisabledClientNeverTouchesTheNetwork() {
        assertEquals(WosOpenUrlClient.Outcome.UNAVAILABLE, client(redirect("x"), false).lookup("10.1/x").outcome());
        assertEquals(0, requests.get());
    }
}
