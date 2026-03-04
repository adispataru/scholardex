package ro.uvt.pokedex.core.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class StartupHealthIndicator implements HealthIndicator {

    private final StartupReadinessTracker startupReadinessTracker;

    public StartupHealthIndicator(StartupReadinessTracker startupReadinessTracker) {
        this.startupReadinessTracker = startupReadinessTracker;
    }

    @Override
    public Health health() {
        Map<String, StartupReadinessTracker.StartupPhaseSnapshot> phases = startupReadinessTracker.snapshot();
        Map<String, Object> phaseDetails = phases.values().stream().collect(Collectors.toMap(
                StartupReadinessTracker.StartupPhaseSnapshot::phase,
                phase -> Map.of(
                        "critical", phase.critical(),
                        "state", phase.state().name(),
                        "durationMs", phase.durationMs(),
                        "lastError", phase.lastError() == null ? "" : phase.lastError()
                )
        ));

        Health.Builder builder = startupReadinessTracker.isCriticalReady()
                ? Health.up()
                : Health.outOfService();

        return builder
                .withDetail("criticalReady", startupReadinessTracker.isCriticalReady())
                .withDetail("phases", phaseDetails)
                .build();
    }
}
