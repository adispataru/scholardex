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
    void latestStatusIsGreenWhenPostgresAndAllSignalsSuccessful() {
        Fixture fixture = fixture(true);

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
        when(fixture.gateService.latestStatus()).thenReturn(new DualReadGateService.DualReadGateStatusSnapshot(
                new DualReadGateService.DualReadGateRunSummary(
                        "gate-success",
                        "SUCCESS",
                        5,
                        1.2d,
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
        assertEquals("SUCCESS", snapshot.dualReadGate().status());
    }

    @Test
    void latestStatusIsYellowForPreCutoverMongoWithIncompleteSignals() {
        Fixture fixture = fixture(false);

        when(fixture.projectionService.latestRunStatus()).thenReturn(new PostgresReportingProjectionService.ProjectionStatusSnapshot(null, Map.of()));
        when(fixture.mvService.latestStatus()).thenReturn(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshStatusSnapshot(null));
        when(fixture.gateService.latestStatus()).thenReturn(new DualReadGateService.DualReadGateStatusSnapshot(null));

        H22OperationalStatusService.H22OperationalStatusSnapshot snapshot = fixture.service.latestStatus();

        assertEquals("YELLOW", snapshot.overallState());
        assertEquals("mongo", snapshot.readStore());
        assertEquals("NO_RUN", snapshot.projection().status());
        assertEquals("NO_RUN", snapshot.materializedViewRefresh().status());
        assertEquals("NO_RUN", snapshot.dualReadGate().status());
    }

    @Test
    void latestStatusIsRedWhenAnyComponentFailed() {
        Fixture fixture = fixture(false);

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
        when(fixture.gateService.latestStatus()).thenReturn(new DualReadGateService.DualReadGateStatusSnapshot(null));

        H22OperationalStatusService.H22OperationalStatusSnapshot snapshot = fixture.service.latestStatus();

        assertEquals("RED", snapshot.overallState());
        assertEquals("FAILED", snapshot.projection().status());
    }

    @Test
    void latestStatusIsRedWhenPostgresModeMissingRequiredSignals() {
        Fixture fixture = fixture(true);

        when(fixture.projectionService.latestRunStatus()).thenReturn(new PostgresReportingProjectionService.ProjectionStatusSnapshot(null, Map.of()));
        when(fixture.mvService.latestStatus()).thenReturn(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshStatusSnapshot(null));
        when(fixture.gateService.latestStatus()).thenReturn(new DualReadGateService.DualReadGateStatusSnapshot(null));

        H22OperationalStatusService.H22OperationalStatusSnapshot snapshot = fixture.service.latestStatus();

        assertEquals("RED", snapshot.overallState());
        assertEquals("postgres", snapshot.readStore());
    }

    private Fixture fixture(boolean postgresMode) {
        ReportingReadStoreSelector selector = mock(ReportingReadStoreSelector.class);
        when(selector.isPostgres()).thenReturn(postgresMode);
        when(selector.readStore()).thenReturn(postgresMode ? ReportingReadStore.POSTGRES : ReportingReadStore.MONGO);

        PostgresReportingProjectionService projectionService = mock(PostgresReportingProjectionService.class);
        PostgresMaterializedViewRefreshService mvService = mock(PostgresMaterializedViewRefreshService.class);
        DualReadGateService gateService = mock(DualReadGateService.class);

        DefaultH22OperationalStatusService service = new DefaultH22OperationalStatusService(
                selector,
                provider(projectionService),
                provider(mvService),
                provider(gateService)
        );

        return new Fixture(service, projectionService, mvService, gateService);
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
            PostgresMaterializedViewRefreshService mvService,
            DualReadGateService gateService
    ) {
    }
}
