package ro.uvt.pokedex.core.observability;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class StartupHealthIndicator implements HealthIndicator {

    private final StartupReadinessTracker startupReadinessTracker;
    private final UserRepository userRepository;

    public StartupHealthIndicator(StartupReadinessTracker startupReadinessTracker, UserRepository userRepository) {
        this.startupReadinessTracker = startupReadinessTracker;
        this.userRepository = userRepository;
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

        boolean adminUserPresent = userRepository.existsByRolesContaining(UserRole.PLATFORM_ADMIN);
        boolean criticalReady = startupReadinessTracker.isCriticalReady();
        Health.Builder builder = startupReadinessTracker.isCriticalReady()
                ? Health.up()
                : Health.outOfService();
        if (!adminUserPresent || !criticalReady) {
            builder = Health.outOfService();
        }

        return builder
                .withDetail("criticalReady", criticalReady)
                .withDetail("adminUserPresent", adminUserPresent)
                .withDetail("phases", phaseDetails)
                .build();
    }
}
