package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultH22OperationalStatusServiceTest {

    @Test
    void latestStatusIsGreenWhenAllSignalsSuccessful() {
        Fixture fixture = fixture();

        when(fixture.projectionService.latestRunStatus()).thenReturn(new PostgresReportingProjectionService.ProjectionStatusSnapshot(
                new PostgresReportingProjectionService.ProjectionRunSummary(
                        "projection-success",
                        "FULL_REBUILD",
                        "SUCCESS",
                        Instant.now(),
                        Instant.now(),
                        List.of(),
                        null
                ),
                Map.of()
        ));
        when(fixture.mvService.latestStatus()).thenReturn(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshStatusSnapshot(
                new PostgresMaterializedViewRefreshService.MaterializedViewRefreshRunSummary(
                        "mv-success",
                        "MANUAL",
                        null,
                        "SUCCESS",
                        Instant.now(),
                        Instant.now(),
                        List.of(),
                        null
                )
        ));

        H22OperationalStatusService.H22OperationalStatusSnapshot snapshot = fixture.service.latestStatus();

        assertEquals("GREEN", snapshot.overallState());
        assertEquals("postgres", snapshot.readStore());
        assertEquals("SUCCESS", snapshot.projection().status());
        assertEquals("SUCCESS", snapshot.materializedViewRefresh().status());
    }

    @Test
    void latestStatusIsGreenWhenSignalsAreIncompleteButNotFailed() {
        Fixture fixture = fixture();

        when(fixture.projectionService.latestRunStatus()).thenReturn(new PostgresReportingProjectionService.ProjectionStatusSnapshot(null, Map.of()));
        when(fixture.mvService.latestStatus()).thenReturn(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshStatusSnapshot(null));

        H22OperationalStatusService.H22OperationalStatusSnapshot snapshot = fixture.service.latestStatus();

        assertEquals("GREEN", snapshot.overallState());
        assertEquals("postgres", snapshot.readStore());
        assertEquals("NO_RUN", snapshot.projection().status());
        assertEquals("NO_RUN", snapshot.materializedViewRefresh().status());
    }

    @Test
    void latestStatusIsRedWhenAnyComponentFailed() {
        Fixture fixture = fixture();

        when(fixture.projectionService.latestRunStatus()).thenReturn(new PostgresReportingProjectionService.ProjectionStatusSnapshot(
                new PostgresReportingProjectionService.ProjectionRunSummary(
                        "projection-failed",
                        "INCREMENTAL_SYNC",
                        "FAILED",
                        Instant.now(),
                        Instant.now(),
                        List.of(),
                        "projection failed"
                ),
                Map.of()
        ));
        when(fixture.mvService.latestStatus()).thenReturn(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshStatusSnapshot(null));

        H22OperationalStatusService.H22OperationalStatusSnapshot snapshot = fixture.service.latestStatus();

        assertEquals("RED", snapshot.overallState());
        assertEquals("FAILED", snapshot.projection().status());
    }

    @Test
    private Fixture fixture() {
        PostgresReportingProjectionService projectionService = mock(PostgresReportingProjectionService.class);
        PostgresMaterializedViewRefreshService mvService = mock(PostgresMaterializedViewRefreshService.class);

        DefaultH22OperationalStatusService service = new DefaultH22OperationalStatusService(
                provider(projectionService),
                provider(mvService)
        );

        return new Fixture(service, projectionService, mvService);
    }

    private <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private record Fixture(
            DefaultH22OperationalStatusService service,
            PostgresReportingProjectionService projectionService,
            PostgresMaterializedViewRefreshService mvService
    ) {
    }
}
