package ro.uvt.pokedex.core.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class StartupHealthIndicatorTest {

    @Test
    void criticalSuccessMarksHealthUp() {
        StartupReadinessTracker tracker = new StartupReadinessTracker();
        tracker.phaseStart("admin-user", true);
        tracker.phaseSuccess("admin-user", 10);
        tracker.phaseStart("domain-bootstrap", true);
        tracker.phaseSuccess("domain-bootstrap", 10);
        tracker.phaseStart("scopus-data-load", true);
        tracker.phaseSuccess("scopus-data-load", 10);

        StartupHealthIndicator indicator = new StartupHealthIndicator(tracker);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsKey("phases");
    }

    @Test
    void criticalFailureMarksOutOfService() {
        StartupReadinessTracker tracker = new StartupReadinessTracker();
        tracker.phaseStart("admin-user", true);
        tracker.phaseFailure("admin-user", 5, "failed");

        StartupHealthIndicator indicator = new StartupHealthIndicator(tracker);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void optionalFailureDoesNotBlockCriticalReadiness() {
        StartupReadinessTracker tracker = new StartupReadinessTracker();
        tracker.phaseStart("admin-user", true);
        tracker.phaseSuccess("admin-user", 10);
        tracker.phaseStart("domain-bootstrap", true);
        tracker.phaseSuccess("domain-bootstrap", 10);
        tracker.phaseStart("scopus-data-load", true);
        tracker.phaseSuccess("scopus-data-load", 10);
        tracker.phaseStart("urap-import", false);
        tracker.phaseFailure("urap-import", 2, "optional failed");

        StartupHealthIndicator indicator = new StartupHealthIndicator(tracker);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
