package ro.uvt.pokedex.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@EnableAsync
@SpringBootApplication
@EnableScheduling
public class CoreApplication {
    @Value("${scopus.python.base-url}")
    private String scopusServiceURL;
    @Value("${openalex.api.base-url:https://api.openalex.org}")
    private String openAlexBaseUrl;

    @Value("${openalex.api.connect-timeout-ms:10000}")
    private long openAlexConnectTimeoutMs;

    @Value("${openalex.api.response-timeout-ms:60000}")
    private long openAlexResponseTimeoutMs;
    @Value("${dblp.api.base-url:https://dblp.org}")
    private String dblpBaseUrl;
    @Value("${crossref.api.base-url:https://api.crossref.org}")
    private String crossrefBaseUrl;
    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }

    @Bean
    public WebClient scopusPythonClient() {
        final int size = (int) DataSize.ofMegabytes(16).toBytes();
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                .build();
        return WebClient.builder()
                .exchangeStrategies(strategies)
                .baseUrl("http://"+scopusServiceURL)
                .build();
    }

    @Bean
    public WebClient openAlexWebClient() {
        // OpenAlex is a keyless public REST API; a 200-result /works page can be large, so allow a
        // generous in-memory buffer (H66B Phase 4a).
        final int size = (int) DataSize.ofMegabytes(32).toBytes();
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                .build();
        // Transport-level deadlines: without them a stalled OpenAlex connection blocks the (single-threaded) sync
        // scheduler until the OS gives up on the socket — minutes to hours, with every queued task waiting behind it.
        final reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.max(1000, openAlexConnectTimeoutMs))
                .responseTimeout(java.time.Duration.ofMillis(Math.max(1000, openAlexResponseTimeoutMs)));
        return WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .baseUrl(openAlexBaseUrl)
                .build();
    }

    @Bean
    public WebClient crossrefWebClient() {
        // H92 — keyless public REST API; a single /works record is small. Access policy is a User-Agent
        // carrying a contact address (the "polite pool"), set per-request by CrossrefClient.
        final int size = (int) DataSize.ofMegabytes(8).toBytes();
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                .build();
        return WebClient.builder()
                .exchangeStrategies(strategies)
                .baseUrl(crossrefBaseUrl)
                .build();
    }

    @Bean
    public WebClient dblpWebClient() {
        // DBLP is a keyless public REST API; a search response is small, but keep a generous buffer (H66B Phase 4b).
        final int size = (int) DataSize.ofMegabytes(16).toBytes();
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                .build();
        return WebClient.builder()
                .exchangeStrategies(strategies)
                .baseUrl(dblpBaseUrl)
                .build();
    }

}
