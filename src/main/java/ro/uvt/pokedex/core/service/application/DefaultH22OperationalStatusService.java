package ro.uvt.pokedex.core.service.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@Service
public class DefaultH22OperationalStatusService implements H22OperationalStatusService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_NO_RUN = "NO_RUN";
    private static final String STATUS_SERVICE_DISABLED = "SERVICE_DISABLED";

    private final ObjectProvider<PostgresReportingProjectionService> projectionServiceProvider;
    private final ObjectProvider<PostgresMaterializedViewRefreshService> materializedViewRefreshServiceProvider;
    private final ObjectProvider<DualReadGateService> dualReadGateServiceProvider;

    public DefaultH22OperationalStatusService(
            ObjectProvider<PostgresReportingProjectionService> projectionServiceProvider,
            ObjectProvider<PostgresMaterializedViewRefreshService> materializedViewRefreshServiceProvider,
            ObjectProvider<DualReadGateService> dualReadGateServiceProvider
    ) {
        this.projectionServiceProvider = projectionServiceProvider;
        this.materializedViewRefreshServiceProvider = materializedViewRefreshServiceProvider;
        this.dualReadGateServiceProvider = dualReadGateServiceProvider;
    }

    @Override
    public H22OperationalStatusSnapshot latestStatus() {
        ComponentStatus projection = projectionComponentStatus();
        ComponentStatus materializedViewRefresh = materializedViewRefreshComponentStatus();
        ComponentStatus dualReadGate = dualReadGateComponentStatus();

        boolean anyFailed = isFailed(projection) || isFailed(materializedViewRefresh) || isFailed(dualReadGate);
        String overallState;
        if (anyFailed) {
            overallState = "RED";
        } else {
            overallState = "GREEN";
        }

        String readStore = "postgres";
        return new H22OperationalStatusSnapshot(
                overallState,
                readStore,
                projection,
                materializedViewRefresh,
                dualReadGate,
                Instant.now()
        );
    }

    private ComponentStatus projectionComponentStatus() {
        PostgresReportingProjectionService service = projectionServiceProvider.getIfAvailable();
        if (service == null) {
            return ComponentStatus.unavailable("projection-service-disabled");
        }
        PostgresReportingProjectionService.ProjectionRunSummary latestRun = service.latestRunStatus().latestRun();
        if (latestRun == null) {
            return new ComponentStatus(STATUS_NO_RUN, null, null, null, "no-projection-run");
        }
        return new ComponentStatus(
                normalizeStatus(latestRun.status()),
                latestRun.runId(),
                latestRun.startedAt(),
                latestRun.completedAt(),
                latestRun.errorSample()
        );
    }

    private ComponentStatus materializedViewRefreshComponentStatus() {
        PostgresMaterializedViewRefreshService service = materializedViewRefreshServiceProvider.getIfAvailable();
        if (service == null) {
            return new ComponentStatus(STATUS_SERVICE_DISABLED, null, null, null, "mv-refresh-service-disabled");
        }
        PostgresMaterializedViewRefreshService.MaterializedViewRefreshRunSummary latestRun = service.latestStatus().latestRun();
        if (latestRun == null) {
            return new ComponentStatus(STATUS_NO_RUN, null, null, null, "no-mv-refresh-run");
        }
        return new ComponentStatus(
                normalizeStatus(latestRun.status()),
                latestRun.runId(),
                latestRun.startedAt(),
                latestRun.completedAt(),
                latestRun.errorSample()
        );
    }

    private ComponentStatus dualReadGateComponentStatus() {
        DualReadGateService service = dualReadGateServiceProvider.getIfAvailable();
        if (service == null) {
            return new ComponentStatus(STATUS_SERVICE_DISABLED, null, null, null, "dual-read-gate-service-disabled");
        }
        DualReadGateService.DualReadGateRunSummary latestRun = service.latestStatus().latestRun();
        if (latestRun == null) {
            return new ComponentStatus(STATUS_NO_RUN, null, null, null, "no-dual-read-gate-run");
        }
        return new ComponentStatus(
                normalizeStatus(latestRun.status()),
                latestRun.runId(),
                latestRun.startedAt(),
                latestRun.completedAt(),
                latestRun.errorSample()
        );
    }

    static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isSuccess(ComponentStatus status) {
        return STATUS_SUCCESS.equals(status.status());
    }

    private boolean isFailed(ComponentStatus status) {
        return STATUS_FAILED.equals(status.status());
    }
}
