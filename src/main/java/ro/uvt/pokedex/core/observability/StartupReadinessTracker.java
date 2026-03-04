package ro.uvt.pokedex.core.observability;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StartupReadinessTracker {

    public enum PhaseState {
        PENDING,
        IN_PROGRESS,
        SUCCESS,
        FAILED
    }

    public record StartupPhaseSnapshot(
            String phase,
            boolean critical,
            PhaseState state,
            long durationMs,
            String lastError
    ) {}

    private static final class StartupPhaseStatus {
        private final boolean critical;
        private volatile PhaseState state;
        private volatile long durationMs;
        private volatile String lastError;

        private StartupPhaseStatus(boolean critical) {
            this.critical = critical;
            this.state = PhaseState.PENDING;
            this.durationMs = 0L;
            this.lastError = "";
        }
    }

    private final Map<String, StartupPhaseStatus> phases = new ConcurrentHashMap<>();

    public StartupReadinessTracker() {
        phases.put("admin-user", new StartupPhaseStatus(true));
        phases.put("domain-bootstrap", new StartupPhaseStatus(true));
        phases.put("scopus-data-load", new StartupPhaseStatus(true));
        phases.put("artistic-events-import", new StartupPhaseStatus(false));
        phases.put("urap-import", new StartupPhaseStatus(false));
        phases.put("cncsis-import", new StartupPhaseStatus(false));
    }

    public void phaseStart(String phase, boolean critical) {
        StartupPhaseStatus status = phases.computeIfAbsent(phase, ignored -> new StartupPhaseStatus(critical));
        status.state = PhaseState.IN_PROGRESS;
        status.lastError = "";
    }

    public void phaseSuccess(String phase, long durationMs) {
        StartupPhaseStatus status = phases.computeIfAbsent(phase, ignored -> new StartupPhaseStatus(false));
        status.state = PhaseState.SUCCESS;
        status.durationMs = durationMs;
        status.lastError = "";
    }

    public void phaseFailure(String phase, long durationMs, String message) {
        StartupPhaseStatus status = phases.computeIfAbsent(phase, ignored -> new StartupPhaseStatus(false));
        status.state = PhaseState.FAILED;
        status.durationMs = durationMs;
        status.lastError = message == null ? "" : message;
    }

    public boolean isCriticalReady() {
        return phases.values().stream()
                .filter(phase -> phase.critical)
                .allMatch(phase -> phase.state == PhaseState.SUCCESS);
    }

    public Map<String, StartupPhaseSnapshot> snapshot() {
        Map<String, StartupPhaseSnapshot> snapshot = new LinkedHashMap<>();
        phases.forEach((name, phase) -> snapshot.put(name, new StartupPhaseSnapshot(
                name,
                phase.critical,
                phase.state,
                phase.durationMs,
                phase.lastError
        )));
        return snapshot;
    }
}
