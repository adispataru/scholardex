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
        return WebClient.builder()
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
