package ro.uvt.pokedex.core.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class ScopusPythonHealthIndicatorTest {

    @Test
    void probeSuccessReturnsUp() {
        ExchangeFunction exchangeFunction = clientRequest ->
                Mono.just(ClientResponse.create(HttpStatus.OK).build());
        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        ScopusPythonHealthIndicator indicator = new ScopusPythonHealthIndicator(webClient, "/health", 2000);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsKey("statusCode");
    }

    @Test
    void probeFailureReturnsDown() {
        ExchangeFunction exchangeFunction = clientRequest ->
                Mono.error(new RuntimeException("timeout"));
        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        ScopusPythonHealthIndicator indicator = new ScopusPythonHealthIndicator(webClient, "/health", 2000);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
