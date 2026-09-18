package ro.uvt.pokedex.core.service.issn;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.HtmlUtils;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the international ISSN register (portal.issn.org) whether an ISSN exists. Measured 2026-09-18: a real ISSN
 * answers 200 with {@code <title>ISSN 1583-7165 - Anale. Seria Informatică (…)</title>}, a wrong check digit 400, a
 * well-formed but unassigned number 404. Anything else (5xx, timeout, a page without that title — e.g. a bot wall)
 * is "could not ask", never "does not exist".
 */
@Slf4j
@Service
public class IssnPortalClient {

    private static final Pattern RECORD_TITLE =
            Pattern.compile("<title>\\s*ISSN\\s+\\d{4}-\\d{3}[\\dXx]\\s+-\\s+(.*?)\\s*</title>", Pattern.DOTALL);

    /** The register's answer; {@code keyTitle} is present only when the ISSN exists. */
    public record Lookup(boolean exists, Optional<String> keyTitle) {
    }

    /** The register could not be asked (network, 5xx, timeout, unrecognised page). */
    public static class IssnPortalUnavailableException extends RuntimeException {
        public IssnPortalUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final WebClient webClient;
    private final Duration timeout;
    private final boolean enabled;

    @Autowired
    public IssnPortalClient(@Value("${issn.portal.base-url:https://portal.issn.org}") String baseUrl,
                            @Value("${issn.portal.timeout-ms:15000}") long timeoutMs,
                            @Value("${issn.portal.enabled:true}") boolean enabled,
                            @Value("${issn.portal.user-agent:ScholarDex/1.0 (mailto:${admin.email:})}") String userAgent) {
        this(WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("User-Agent", userAgent)
                        .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                                .responseTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))))
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                        .build(),
                timeoutMs, enabled);
    }

    IssnPortalClient(WebClient webClient, long timeoutMs, boolean enabled) {
        this.webClient = webClient;
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.enabled = enabled;
    }

    /** @throws IssnPortalUnavailableException when the register could not give a yes/no answer */
    public Lookup lookup(String normalizedIssn) {
        if (!enabled) {
            throw new IssnPortalUnavailableException("ISSN portal lookup disabled (issn.portal.enabled=false)", null);
        }
        String body;
        try {
            body = webClient.get()
                    .uri("/resource/ISSN/{issn}", normalizedIssn)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 400) {
                return new Lookup(false, Optional.empty());
            }
            throw new IssnPortalUnavailableException("ISSN portal answered HTTP " + status, e);
        } catch (RuntimeException e) {
            throw new IssnPortalUnavailableException("ISSN portal unreachable: " + e, e);
        }
        Matcher m = body == null ? null : RECORD_TITLE.matcher(body);
        if (m == null || !m.find()) {
            throw new IssnPortalUnavailableException("ISSN portal returned a page without a record title", null);
        }
        return new Lookup(true, Optional.of(HtmlUtils.htmlUnescape(m.group(1)).trim()));
    }
}
