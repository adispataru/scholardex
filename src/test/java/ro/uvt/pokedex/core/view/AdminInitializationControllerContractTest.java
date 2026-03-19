package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.service.application.GeneralInitializationService;
import ro.uvt.pokedex.core.service.application.H22OperationalStatusService;
import ro.uvt.pokedex.core.service.application.PostgresMaterializedViewRefreshService;
import ro.uvt.pokedex.core.service.application.PostgresReportingProjectionService;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.ScopusBigBangMigrationService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.application.UserDefinedMaintenanceOrchestrationService;
import ro.uvt.pokedex.core.service.application.WosBigBangMigrationService;
import ro.uvt.pokedex.core.service.application.model.WosEnrichmentRunSummaryDto;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminInitializationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminInitializationControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RankingMaintenanceFacade rankingMaintenanceFacade;
    @MockitoBean
    private ScopusBigBangMigrationService scopusBigBangMigrationService;
    @MockitoBean
    private GeneralInitializationService generalInitializationService;
    @MockitoBean
    private PostgresReportingProjectionService postgresReportingProjectionService;
    @MockitoBean
    private PostgresMaterializedViewRefreshService postgresMaterializedViewRefreshService;
    @MockitoBean
    private H22OperationalStatusService h22OperationalStatusService;
    @MockitoBean
    private UserDefinedMaintenanceOrchestrationService userDefinedMaintenanceOrchestrationService;

    @Test
    void initializationPageRendersTemplate() throws Exception {
        mockMvc.perform(get("/admin/initialization"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/initialization"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/wos/ingest")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/wos/buildFacts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/wos/enrichCategoryRankings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/wos/enrichment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/wos/rebuildProjections")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/wos/ensureIndexes")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/wos/resetCanonicalState")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/resetCanonicalState")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/backfillCanonicalCitations")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/buildCanonical")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/showTouchQueueBacklog")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/rebuildTouchQueuesFromEvents")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/drainTouchQueues")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/reconcileEdges")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/resetCanonicalCheckpoints")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/user-defined/buildFacts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/user-defined/canonicalize")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/user-defined/runAll")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/postgres/projection/runFull")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/postgres/projection/runIncremental")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/postgres/projection/showStatus")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/postgres/projection/resetState")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/postgres/materialized/refreshAll")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/postgres/materialized/refreshSlice")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/postgres/materialized/showStatus")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/postgres/operational/showStatus")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/postgres/operational/status")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("docs/operational-playbook.md")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/general/runAll")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/general/adminUser")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/general/domain")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/general/artisticEvents")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/general/urap")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/general/cncsis")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/general/coreConference")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/general/sense")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/admin/initialization/wos/runBigBangMigration"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/runBigBang"))));
    }

    @Test
    void runUserDefinedMaintenanceActionsRedirectToInitializationPage() throws Exception {
        ImportProcessingResult buildFacts = new ImportProcessingResult(10);
        buildFacts.markProcessed();
        buildFacts.markImported();
        ImportProcessingResult canonicalize = new ImportProcessingResult(10);
        canonicalize.markProcessed();
        canonicalize.markUpdated();
        ImportProcessingResult edgeReconcile = new ImportProcessingResult(10);
        edgeReconcile.markUpdated();
        ImportProcessingResult projections = new ImportProcessingResult(10);
        projections.markProcessed();

        when(userDefinedMaintenanceOrchestrationService.runBuildFactsStep(null)).thenReturn(buildFacts);
        when(userDefinedMaintenanceOrchestrationService.runCanonicalizeStep(true, true, true))
                .thenReturn(new UserDefinedMaintenanceOrchestrationService.UserDefinedMaintenanceRunSummary(
                        new ImportProcessingResult(0),
                        canonicalize,
                        new ScholardexSourceLinkService.ImportRepairSummary(1L, 0L, 0L),
                        edgeReconcile,
                        projections
                ));
        when(userDefinedMaintenanceOrchestrationService.runAll(null, true, true, true))
                .thenReturn(new UserDefinedMaintenanceOrchestrationService.UserDefinedMaintenanceRunSummary(
                        buildFacts,
                        canonicalize,
                        new ScholardexSourceLinkService.ImportRepairSummary(1L, 0L, 0L),
                        edgeReconcile,
                        projections
                ));

        mockMvc.perform(post("/admin/initialization/user-defined/buildFacts"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/user-defined/canonicalize")
                        .param("reconcileSourceLinks", "true")
                        .param("reconcileEdges", "true")
                        .param("rebuildProjections", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/user-defined/runAll")
                        .param("reconcileSourceLinks", "true")
                        .param("reconcileEdges", "true")
                        .param("rebuildProjections", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(userDefinedMaintenanceOrchestrationService).runBuildFactsStep(null);
        verify(userDefinedMaintenanceOrchestrationService).runCanonicalizeStep(true, true, true);
        verify(userDefinedMaintenanceOrchestrationService).runAll(null, true, true, true);
    }

    @Test
    void runGeneralInitializationAllRedirectsToInitializationPage() throws Exception {
        when(generalInitializationService.runAll())
                .thenReturn(new GeneralInitializationService.GeneralInitializationRunSummary(
                        "run-all",
                        Instant.now(),
                        List.of(
                                new GeneralInitializationService.GeneralInitializationStepResult(
                                        "admin-user", true, true, 10L, Instant.now(), Instant.now(), "ok"
                                )
                        )
                ));

        mockMvc.perform(post("/admin/initialization/general/runAll"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(generalInitializationService).runAll();
    }

    @Test
    void runGeneralInitializationStepRedirectsToInitializationPage() throws Exception {
        when(generalInitializationService.runAdminUserBootstrap())
                .thenReturn(new GeneralInitializationService.GeneralInitializationStepResult(
                        "admin-user", true, true, 10L, Instant.now(), Instant.now(), "ok"
                ));
        when(generalInitializationService.runSpecialDomainBootstrap())
                .thenReturn(new GeneralInitializationService.GeneralInitializationStepResult(
                        "special-domain-all", true, true, 10L, Instant.now(), Instant.now(), "ok"
                ));
        when(generalInitializationService.runArtisticEventsImport())
                .thenReturn(new GeneralInitializationService.GeneralInitializationStepResult(
                        "artistic-events", true, false, 10L, Instant.now(), Instant.now(), "ok"
                ));
        when(generalInitializationService.runUrapImport())
                .thenReturn(new GeneralInitializationService.GeneralInitializationStepResult(
                        "urap", true, false, 10L, Instant.now(), Instant.now(), "ok"
                ));
        when(generalInitializationService.runCncsisImport())
                .thenReturn(new GeneralInitializationService.GeneralInitializationStepResult(
                        "cncsis", true, false, 10L, Instant.now(), Instant.now(), "ok"
                ));
        when(generalInitializationService.runCoreConferenceImport())
                .thenReturn(new GeneralInitializationService.GeneralInitializationStepResult(
                        "core-conference", true, false, 10L, Instant.now(), Instant.now(), "ok"
                ));
        when(generalInitializationService.runSenseImport())
                .thenReturn(new GeneralInitializationService.GeneralInitializationStepResult(
                        "sense", true, false, 10L, Instant.now(), Instant.now(), "ok"
                ));

        mockMvc.perform(post("/admin/initialization/general/adminUser"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/general/domain"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/general/artisticEvents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/general/urap"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/general/cncsis"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/general/coreConference"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/general/sense"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(generalInitializationService).runAdminUserBootstrap();
        verify(generalInitializationService).runSpecialDomainBootstrap();
        verify(generalInitializationService).runArtisticEventsImport();
        verify(generalInitializationService).runUrapImport();
        verify(generalInitializationService).runCncsisImport();
        verify(generalInitializationService).runCoreConferenceImport();
        verify(generalInitializationService).runSenseImport();
    }

    @Test
    void runPostgresProjectionActionsRedirectToInitializationPage() throws Exception {
        when(postgresReportingProjectionService.runFullRebuild())
                .thenReturn(new PostgresReportingProjectionService.ProjectionRunSummary(
                        "run-full",
                        "FULL_REBUILD",
                        "SUCCESS",
                        Instant.now(),
                        Instant.now(),
                        List.of(),
                        null
                ));
        when(postgresReportingProjectionService.runIncrementalSync())
                .thenReturn(new PostgresReportingProjectionService.ProjectionRunSummary(
                        "run-inc",
                        "INCREMENTAL_SYNC",
                        "SUCCESS",
                        Instant.now(),
                        Instant.now(),
                        List.of(),
                        null
                ));
        when(postgresReportingProjectionService.latestRunStatus())
                .thenReturn(new PostgresReportingProjectionService.ProjectionStatusSnapshot(
                        new PostgresReportingProjectionService.ProjectionRunSummary(
                                "run-inc",
                                "INCREMENTAL_SYNC",
                                "SUCCESS",
                                Instant.now(),
                                Instant.now(),
                                List.of(),
                                null
                        ),
                        java.util.Map.of()
                ));

        mockMvc.perform(post("/admin/initialization/postgres/projection/runFull"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/postgres/projection/runIncremental"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/postgres/projection/showStatus"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/postgres/projection/resetState")
                        .param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(postgresReportingProjectionService).runFullRebuild();
        verify(postgresReportingProjectionService).runIncrementalSync();
        verify(postgresReportingProjectionService).latestRunStatus();
        verify(postgresReportingProjectionService).resetProjectionState();
    }

    @Test
    void runPostgresMaterializedViewActionsRedirectToInitializationPage() throws Exception {
        when(postgresMaterializedViewRefreshService.refreshAllManual())
                .thenReturn(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshRunSummary(
                        "mv-run-all",
                        "MANUAL",
                        null,
                        "SUCCESS",
                        Instant.now(),
                        Instant.now(),
                        List.of(),
                        null
                ));
        when(postgresMaterializedViewRefreshService.refreshManualForSlices(java.util.Set.of("wos")))
                .thenReturn(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshRunSummary(
                        "mv-run-wos",
                        "MANUAL",
                        null,
                        "SUCCESS",
                        Instant.now(),
                        Instant.now(),
                        List.of(),
                        null
                ));
        when(postgresMaterializedViewRefreshService.latestStatus())
                .thenReturn(new PostgresMaterializedViewRefreshService.MaterializedViewRefreshStatusSnapshot(
                        new PostgresMaterializedViewRefreshService.MaterializedViewRefreshRunSummary(
                                "mv-run-last",
                                "MANUAL",
                                null,
                                "SUCCESS",
                                Instant.now(),
                                Instant.now(),
                                List.of(),
                                null
                        )
                ));

        mockMvc.perform(post("/admin/initialization/postgres/materialized/refreshAll"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/postgres/materialized/refreshSlice")
                        .param("slice", "wos"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/postgres/materialized/showStatus"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(get("/admin/initialization/postgres/materialized/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRun.runId").value("mv-run-last"));

        verify(postgresMaterializedViewRefreshService).refreshAllManual();
        verify(postgresMaterializedViewRefreshService).refreshManualForSlices(java.util.Set.of("wos"));
        verify(postgresMaterializedViewRefreshService, times(2)).latestStatus();
    }

    @Test
    void showPostgresOperationalStatusActionsRedirectToInitializationPage() throws Exception {
        when(h22OperationalStatusService.latestStatus())
                .thenReturn(new H22OperationalStatusService.H22OperationalStatusSnapshot(
                        "GREEN",
                        "postgres",
                        new H22OperationalStatusService.ComponentStatus("SUCCESS", "projection-1", Instant.now(), Instant.now(), null),
                        new H22OperationalStatusService.ComponentStatus("SUCCESS", "mv-1", Instant.now(), Instant.now(), null),
                        Instant.now()
                ));

        mockMvc.perform(post("/admin/initialization/postgres/operational/showStatus"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(get("/admin/initialization/postgres/operational/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallState").value("GREEN"))
                .andExpect(jsonPath("$.readStore").value("postgres"))
                .andExpect(jsonPath("$.projection.status").value("SUCCESS"));

        verify(h22OperationalStatusService, times(2)).latestStatus();
    }

    @Test
    void wosEnrichmentPageRendersTemplate() throws Exception {
        when(rankingMaintenanceFacade.latestWosCategoryRankingEnrichmentSummary())
                .thenReturn(new WosEnrichmentRunSummaryDto(
                        "enrich-category-rankings",
                        false,
                        null,
                        null,
                        0L,
                        0,
                        0,
                        0,
                        0,
                        0,
                        "not-run"
                ));

        mockMvc.perform(get("/admin/initialization/wos/enrichment"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/wos-enrichment"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/wos/enrichment/runPage")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/wos/enrichment/summary")));

        verify(rankingMaintenanceFacade).latestWosCategoryRankingEnrichmentSummary();
    }

    @Test
    void ingestWosRedirectsToInitializationPage() throws Exception {
        when(rankingMaintenanceFacade.ingestWosEvents(eq("v2026")))
                .thenReturn(new MigrationStepResult(
                        "ingest",
                        true,
                        100,
                        80,
                        0,
                        20,
                        0,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ));

        mockMvc.perform(post("/admin/initialization/wos/ingest")
                        .param("sourceVersion", "v2026"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(rankingMaintenanceFacade).ingestWosEvents("v2026");
    }

    @Test
    void resetWosFactCheckpointRedirectsToInitializationPage() throws Exception {
        mockMvc.perform(post("/admin/initialization/wos/resetFactCheckpoint"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(rankingMaintenanceFacade).resetWosFactBuildCheckpoint();
    }

    @Test
    void buildWosFactsRedirectsToInitializationPage() throws Exception {
        when(rankingMaintenanceFacade.buildWosFactsFromEvents(eq(200), eq("v2026"), eq(true)))
                .thenReturn(new MigrationStepResult(
                        "build-facts",
                        true,
                        1000,
                        800,
                        200,
                        0,
                        0,
                        null,
                        List.of(),
                        200,
                        205,
                        6,
                        null,
                        true,
                        199
                ));

        mockMvc.perform(post("/admin/initialization/wos/buildFacts")
                        .param("sourceVersion", "v2026")
                        .param("startBatchOverride", "200"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(rankingMaintenanceFacade).buildWosFactsFromEvents(200, "v2026", true);
    }

    @Test
    void enrichWosCategoryRankingsRedirectsToInitializationPage() throws Exception {
        when(rankingMaintenanceFacade.runWosCategoryRankingEnrichmentWithSummary())
                .thenReturn(new WosEnrichmentRunSummaryDto(
                        "enrich-category-rankings",
                        true,
                        Instant.now(),
                        Instant.now(),
                        1000L,
                        1000,
                        240,
                        760,
                        0,
                        760,
                        null
                ));

        mockMvc.perform(post("/admin/initialization/wos/enrichCategoryRankings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(rankingMaintenanceFacade).runWosCategoryRankingEnrichmentWithSummary();
    }

    @Test
    void runWosCategoryEnrichmentApiReturnsSummaryJson() throws Exception {
        when(rankingMaintenanceFacade.runWosCategoryRankingEnrichmentWithSummary())
                .thenReturn(new WosEnrichmentRunSummaryDto(
                        "enrich-category-rankings",
                        true,
                        Instant.parse("2026-03-08T09:00:00Z"),
                        Instant.parse("2026-03-08T09:00:05Z"),
                        5000L,
                        1000,
                        240,
                        760,
                        0,
                        760,
                        null
                ));

        mockMvc.perform(post("/admin/initialization/wos/enrichment/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepName").value("enrich-category-rankings"))
                .andExpect(jsonPath("$.executed").value(true))
                .andExpect(jsonPath("$.processed").value(1000))
                .andExpect(jsonPath("$.computed").value(240))
                .andExpect(jsonPath("$.preserved").value(760))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.skipped").value(760));

        verify(rankingMaintenanceFacade).runWosCategoryRankingEnrichmentWithSummary();
    }

    @Test
    void runWosCategoryEnrichmentPageFlowRedirectsToDedicatedPage() throws Exception {
        when(rankingMaintenanceFacade.runWosCategoryRankingEnrichmentWithSummary())
                .thenReturn(new WosEnrichmentRunSummaryDto(
                        "enrich-category-rankings",
                        true,
                        Instant.now(),
                        Instant.now(),
                        10L,
                        10,
                        2,
                        8,
                        0,
                        8,
                        null
                ));

        mockMvc.perform(post("/admin/initialization/wos/enrichment/runPage"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization/wos/enrichment"));

        verify(rankingMaintenanceFacade).runWosCategoryRankingEnrichmentWithSummary();
    }

    @Test
    void getWosCategoryEnrichmentSummaryApiReturnsLatestSummaryJson() throws Exception {
        when(rankingMaintenanceFacade.latestWosCategoryRankingEnrichmentSummary())
                .thenReturn(new WosEnrichmentRunSummaryDto(
                        "enrich-category-rankings",
                        false,
                        null,
                        null,
                        0L,
                        0,
                        0,
                        0,
                        0,
                        0,
                        "not-run"
                ));

        mockMvc.perform(get("/admin/initialization/wos/enrichment/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepName").value("enrich-category-rankings"))
                .andExpect(jsonPath("$.executed").value(false))
                .andExpect(jsonPath("$.processed").value(0))
                .andExpect(jsonPath("$.computed").value(0))
                .andExpect(jsonPath("$.preserved").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.note").value("not-run"));

        verify(rankingMaintenanceFacade).latestWosCategoryRankingEnrichmentSummary();
    }

    @Test
    void resetWosCanonicalStateRedirectsToInitializationPage() throws Exception {
        when(rankingMaintenanceFacade.resetWosCanonicalState())
                .thenReturn(new WosBigBangMigrationService.CanonicalResetResult(10, 8, 6, 6, 2, 3, 7, 7));

        mockMvc.perform(post("/admin/initialization/wos/resetCanonicalState")
                        .param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(rankingMaintenanceFacade).resetWosCanonicalState();
    }

    @Test
    void resetWosCanonicalStateWithoutResetConfirmationDoesNotExecute() throws Exception {
        mockMvc.perform(post("/admin/initialization/wos/resetCanonicalState")
                        .param("confirmation", "reset"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        org.mockito.Mockito.verifyNoInteractions(rankingMaintenanceFacade);
    }

    @Test
    void runScopusCitationBackfillRedirectsToInitializationPage() throws Exception {
        ImportProcessingResult result = new ImportProcessingResult(10);
        result.markProcessed();
        result.markImported();
        when(scopusBigBangMigrationService.runCitationIdentityBackfill()).thenReturn(result);

        mockMvc.perform(post("/admin/initialization/scopus/backfillCanonicalCitations"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).runCitationIdentityBackfill();
    }

    @Test
    void runScopusCanonicalBuildRedirectsToInitializationPage() throws Exception {
        ImportProcessingResult result = new ImportProcessingResult(10);
        result.markProcessed();
        result.markImported();
        result.setStartBatch(2);
        result.setEndBatch(3);
        result.setBatchesProcessed(2);
        result.setTotalBatches(10);
        result.setResumedFromCheckpoint(true);
        result.setCheckpointLastCompletedBatch(1);
        when(scopusBigBangMigrationService.runCanonicalBuildStep(eq("citation"), eq(2), eq(true), eq(500), eq(false), eq(false), eq(false), eq(true))).thenReturn(result);

        mockMvc.perform(post("/admin/initialization/scopus/buildCanonical")
                        .param("entity", "citation")
                        .param("startBatchOverride", "2")
                        .param("chunkSizeOverride", "500")
                        .param("useCheckpoint", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).runCanonicalBuildStep("citation", 2, true, 500, false, false, false, true);
    }

    @Test
    void touchQueueMaintenanceActionsRedirectToInitializationPage() throws Exception {
        when(scopusBigBangMigrationService.showTouchQueueBacklog())
                .thenReturn(new ro.uvt.pokedex.core.service.importing.scopus.ScopusTouchQueueService.TouchBacklog(1, 2, 3, 4, 5));
        when(scopusBigBangMigrationService.rebuildTouchQueuesFromEvents())
                .thenReturn(new ro.uvt.pokedex.core.service.importing.scopus.ScopusTouchQueueService.TouchBacklog(5, 4, 3, 2, 1));
        when(scopusBigBangMigrationService.drainAllTouchQueues())
                .thenReturn(new ro.uvt.pokedex.core.service.importing.scopus.ScopusTouchQueueService.TouchBacklog(0, 0, 0, 0, 0));

        mockMvc.perform(post("/admin/initialization/scopus/showTouchQueueBacklog"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/scopus/rebuildTouchQueuesFromEvents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));
        mockMvc.perform(post("/admin/initialization/scopus/drainTouchQueues"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).showTouchQueueBacklog();
        verify(scopusBigBangMigrationService).rebuildTouchQueuesFromEvents();
        verify(scopusBigBangMigrationService).drainAllTouchQueues();
    }

    @Test
    void runScopusSourceLinkReconcileRedirectsToInitializationPage() throws Exception {
        when(scopusBigBangMigrationService.runSourceLinkReconcileStep())
                .thenReturn(new ScholardexSourceLinkService.ImportRepairSummary(10L, 5L, 0L));

        mockMvc.perform(post("/admin/initialization/scopus/reconcileSourceLinks"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).runSourceLinkReconcileStep();
    }

    @Test
    void runScopusEdgeReconcileRedirectsToInitializationPage() throws Exception {
        ImportProcessingResult result = new ImportProcessingResult(10);
        result.markProcessed();
        result.markUpdated();
        when(scopusBigBangMigrationService.runEdgeReconcileStep()).thenReturn(result);

        mockMvc.perform(post("/admin/initialization/scopus/reconcileEdges"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).runEdgeReconcileStep();
    }

    @Test
    void resetScopusCanonicalCheckpointsRedirectsToInitializationPage() throws Exception {
        mockMvc.perform(post("/admin/initialization/scopus/resetCanonicalCheckpoints"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).resetCanonicalBuildCheckpoints();
    }

    @Test
    void resetScopusCanonicalStateRedirectsToInitializationPage() throws Exception {
        when(scopusBigBangMigrationService.resetCanonicalState())
                .thenReturn(new ScopusBigBangMigrationService.CanonicalResetResult(
                        10, 5, 5, 2, 3, 4, 2, 3, 4,
                        5, 5, 3, 4, 2, 5, 3, 4, 2, 6, 1, 7, 8, 9, 4,
                        11, 12, 13, 14, 15
                ));

        mockMvc.perform(post("/admin/initialization/scopus/resetCanonicalState")
                        .param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).resetCanonicalState();
    }

    @Test
    void resetScopusCanonicalStateWithoutResetConfirmationDoesNotExecute() throws Exception {
        mockMvc.perform(post("/admin/initialization/scopus/resetCanonicalState")
                        .param("confirmation", "reset"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        org.mockito.Mockito.verifyNoInteractions(scopusBigBangMigrationService);
    }

}
