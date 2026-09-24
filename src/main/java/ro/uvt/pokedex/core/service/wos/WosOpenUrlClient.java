package ro.uvt.pokedex.core.service.wos;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a DOI to its Web of Science accession number ("UT", {@code WOS:000562375500001}) through Clarivate's
 * keyless OpenURL gateway: {@code GET /cps/openurl/service?rft_id=info:doi/<doi>} answers with a 302 whose
 * {@code Location} carries {@code KeyUT=WOS:…} for an indexed record (journal articles, book chapters and CPCI
 * proceedings alike — measured 2026-09-24), or points at {@code OpenURLNoRecord.html} when WoS has no record.
 *
 * <p>Replaces a resolver that shelled out to {@code curl}, which the container does not have, so the CNFIS
 * export's WoS-code column had been empty since the containerised deployment. Redirects are NOT followed:
 * the answer is the redirect itself. One request per DOI, throttled; every failure has a deadline.
 */
@Slf4j
@Service
public class WosOpenUrlClient {

    private static final Pattern KEY_UT = Pattern.compile("KeyUT=(WOS:[0-9A-Za-z]+)");

    /** Outcome of one lookup; {@code UNAVAILABLE} = the gateway could not be asked (never cached as "no record"). */
    public enum Outcome { FOUND, NOT_FOUND, UNAVAILABLE }

    public record Lookup(Outcome outcome, Optional<String> wosId) {
        public static Lookup found(String id) { return new Lookup(Outcome.FOUND, Optional.of(id)); }
        public static Lookup notFound() { return new Lookup(Outcome.NOT_FOUND, Optional.empty()); }
        public static Lookup unavailable() { return new Lookup(Outcome.UNAVAILABLE, Optional.empty()); }
    }

    private final WebClient webClient;
    private final Duration timeout;
    private final long minIntervalMs;
    private final boolean enabled;
    private long lastRequestAt = 0L;

    @Autowired
    public WosOpenUrlClient(@Value("${wos.openurl.base-url:https://ws.isiknowledge.com}") String baseUrl,
                            @Value("${wos.openurl.timeout-ms:15000}") long timeoutMs,
                            @Value("${wos.openurl.min-interval-ms:250}") long minIntervalMs,
                            @Value("${wos.openurl.enabled:true}") boolean enabled,
                            @Value("${wos.openurl.user-agent:ScholarDex/1.0 (mailto:${admin.email:})}") String userAgent) {
        this(WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("User-Agent", userAgent)
                        .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                                .followRedirect(false)
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                                .responseTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))))
                        .build(),
                timeoutMs, minIntervalMs, enabled);
    }

    WosOpenUrlClient(WebClient webClient, long timeoutMs, long minIntervalMs, boolean enabled) {
        this.webClient = webClient;
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.minIntervalMs = Math.max(0, minIntervalMs);
        this.enabled = enabled;
    }

    public synchronized Lookup lookup(String doi) {
        if (!enabled || doi == null || doi.isBlank()) {
            return Lookup.unavailable();
        }
        throttle();
        try {
            ClientResponse response = webClient.get()
                    .uri(builder -> builder.path("/cps/openurl/service")
                            .queryParam("url_ver", "Z39.88-2004")
                            .queryParam("rft_id", "info:doi/" + doi.trim())
                            .build())
                    .exchangeToMono(reactor.core.publisher.Mono::just)
                    .timeout(timeout)
                    .block();
            if (response == null) {
                return Lookup.unavailable();
            }
            int status = response.statusCode().value();
            URI location = response.headers().asHttpHeaders().getLocation();
            response.releaseBody().subscribe();
            if (status >= 300 && status < 400 && location != null) {
                Matcher m = KEY_UT.matcher(location.toString());
                if (m.find()) {
                    return Lookup.found(m.group(1));
                }
                if (location.toString().contains("OpenURLNoRecord")) {
                    return Lookup.notFound();
                }
                log.warn("WoS OpenURL redirect without KeyUT for DOI {}: {}", doi, location);
                return Lookup.unavailable();
            }
            log.warn("WoS OpenURL answered HTTP {} for DOI {}", status, doi);
            return Lookup.unavailable();
        } catch (RuntimeException e) {
            log.warn("WoS OpenURL lookup failed for DOI {}: {}", doi, e.toString());
            return Lookup.unavailable();
        }
    }

    private void throttle() {
        long wait = minIntervalMs - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }
}
