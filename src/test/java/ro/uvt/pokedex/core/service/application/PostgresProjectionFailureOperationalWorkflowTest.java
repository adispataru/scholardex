package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import ro.uvt.pokedex.core.view.AdminInitializationController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresProjectionFailureOperationalWorkflowTest {

    @Test
    void failedProjectionRunTurnsOperationalStatusRedAndRemainsVisibleToOperators() {
        PostgresReportingProjectionService projectionService = mock(PostgresReportingProjectionService.class);
        PostgresMaterializedViewRefreshService materializedViewRefreshService = mock(PostgresMaterializedViewRefreshService.class);
        Instant now = Instant.parse("2026-03-30T10:15:30Z");

        PostgresReportingProjectionService.ProjectionRunSummary failedRun =
                new PostgresReportingProjectionService.ProjectionRunSummary(
                        "projection-failed-1",
                        "INCREMENTAL_SYNC",
                        "FAILED",
                        now,
                        now,
                        List.of(new PostgresReportingProjectionService.SliceRunSummary(
                                "wos",
                                "FAILED",
                                "wos:2026-03",
                                0,
                                "checksum mismatch",
                                now,
                                now
                        )),
                        "projection failed for wos slice"
                );
        when(projectionService.latestRunStatus()).thenReturn(
                new PostgresReportingProjectionService.ProjectionStatusSnapshot(
                        failedRun,
                        Map.of("wos", new PostgresReportingProjectionService.CheckpointSummary(
                                "wos",
                                "wos:2026-02",
                                "projection-success-previous",
                                now.minusSeconds(3600),
                                "FULL_REBUILD"
                        ))
                )
        );
        when(materializedViewRefreshService.latestStatus()).thenReturn(
                new PostgresMaterializedViewRefreshService.MaterializedViewRefreshStatusSnapshot(
                        new PostgresMaterializedViewRefreshService.MaterializedViewRefreshRunSummary(
                                "mv-success-1",
                                "MANUAL",
                                null,
                                "SUCCESS",
                                now.minusSeconds(60),
                                now.minusSeconds(30),
                                List.of(),
                                null
                        )
                )
        );

        DefaultPostgresOperationalStatusService operationalStatusService = new DefaultPostgresOperationalStatusService(
                provider(projectionService),
                provider(materializedViewRefreshService)
        );
        AdminInitializationController controller = new AdminInitializationController(
                mock(GeneralInitializationService.class),
                mock(RankingMaintenanceFacade.class),
                mock(ScopusBigBangMigrationService.class),
                mock(ro.uvt.pokedex.core.service.importing.ScopusDataService.class),
                mock(ro.uvt.pokedex.core.service.importing.wos.WosImportEventIngestionService.class),
                mock(PipelineRebuildService.class),
                mock(UserDefinedMaintenanceOrchestrationService.class),
                provider(projectionService),
                provider(materializedViewRefreshService),
                provider(operationalStatusService),
                mock(ro.uvt.pokedex.core.service.application.ForumReconcileAuditService.class),
                mock(ro.uvt.pokedex.core.service.importing.DoajDataService.class),
                mock(ro.uvt.pokedex.core.service.importing.ErihDataService.class),
                mock(ro.uvt.pokedex.core.service.application.ErihOnboardingService.class),
                mock(ro.uvt.pokedex.core.service.application.DoajOnboardingService.class),
                mock(ro.uvt.pokedex.core.service.application.ScholardexForumDeduplicationService.class),
                mock(ro.uvt.pokedex.core.service.application.ForumReconcileService.class),
                mock(ro.uvt.pokedex.core.service.application.ScholardexAffiliationRorBridgeService.class),
                mock(ro.uvt.pokedex.core.service.application.AuthorReconcileService.class)
        );

        PostgresOperationalStatusService.PostgresOperationalStatusSnapshot operational = controller.postgresOperationalStatusApi();
        PostgresReportingProjectionService.ProjectionStatusSnapshot projection = controller.postgresProjectionStatusApi();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String redirect = controller.showPostgresOperationalStatus(redirectAttributes);

        assertEquals("RED", operational.overallState());
        assertEquals("postgres", operational.readStore());
        assertEquals("FAILED", operational.projection().status());
        assertEquals("projection-failed-1", operational.projection().runId());
        assertEquals("SUCCESS", operational.materializedViewRefresh().status());

        assertNotNull(projection.latestRun());
        assertEquals("projection-failed-1", projection.latestRun().runId());
        assertEquals("FAILED", projection.latestRun().status());
        assertEquals("projection failed for wos slice", projection.latestRun().errorSample());
        assertEquals(1, projection.latestRun().slices().size());
        assertEquals("wos", projection.latestRun().slices().getFirst().sliceName());
        assertEquals("FAILED", projection.latestRun().slices().getFirst().status());

        assertEquals("redirect:/admin/initialization", redirect);
        Object successMessage = redirectAttributes.getFlashAttributes().get("successMessage");
        assertInstanceOf(String.class, successMessage);
        assertTrue(((String) successMessage).contains("state=RED"));
        assertTrue(((String) successMessage).contains("projection=FAILED"));
        assertTrue(((String) successMessage).contains("materialized=SUCCESS"));
    }

    private <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
