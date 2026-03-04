package ro.uvt.pokedex.core.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class ScopusPythonHealthIndicator implements HealthIndicator {

    private final WebClient scopusPythonClient;
    private final String healthPath;
    private final Duration timeout;

    public ScopusPythonHealthIndicator(
            WebClient scopusPythonClient,
            @Value("${scopus.python.health-path:/health}") String healthPath,
            @Value("${scopus.python.health-timeout-ms:2000}") long timeoutMs
    ) {
        this.scopusPythonClient = scopusPythonClient;
        this.healthPath = healthPath;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public Health health() {
        long startedAt = System.currentTimeMillis();
        try {
            int statusCode = scopusPythonClient.get()
                    .uri(healthPath)
                    .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode()))
                    .map(HttpStatusCode::value)
                    .timeout(timeout)
                    .blockOptional()
                    .orElse(503);

            return Health.up()
                    .withDetail("statusCode", statusCode)
                    .withDetail("durationMs", System.currentTimeMillis() - startedAt)
                    .withDetail("healthPath", healthPath)
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("durationMs", System.currentTimeMillis() - startedAt)
                    .withDetail("healthPath", healthPath)
                    .build();
        }
    }
}
