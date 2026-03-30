package ro.uvt.pokedex.core.service.application;

import java.time.Instant;

public interface PostgresOperationalStatusService {

    PostgresOperationalStatusSnapshot latestStatus();

    record PostgresOperationalStatusSnapshot(
            String overallState,
            String readStore,
            ComponentStatus projection,
            ComponentStatus materializedViewRefresh,
            Instant evaluatedAt
    ) {
        public static PostgresOperationalStatusSnapshot unavailable() {
            Instant now = Instant.now();
            ComponentStatus unavailable = ComponentStatus.unavailable("service-unavailable");
            return new PostgresOperationalStatusSnapshot(
                    "RED",
                    "unknown",
                    unavailable,
                    unavailable,
                    now
            );
        }
    }

    record ComponentStatus(
            String status,
            String runId,
            Instant startedAt,
            Instant completedAt,
            String detail
    ) {
        public static ComponentStatus unavailable(String detail) {
            return new ComponentStatus("UNAVAILABLE", null, null, null, detail);
        }
    }
}
