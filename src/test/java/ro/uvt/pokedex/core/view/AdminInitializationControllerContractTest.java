package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.ScopusBigBangMigrationService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.application.WosBigBangMigrationService;
import ro.uvt.pokedex.core.service.application.model.WosEnrichmentRunSummaryDto;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/reconcileEdges")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/resetCanonicalCheckpoints")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/admin/initialization/wos/runBigBangMigration"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/admin/initialization/scopus/runBigBang"))));
    }

    @Test
    void runWosBigBangRedirectsToInitializationPage() throws Exception {
        when(rankingMaintenanceFacade.runWosBigBangMigration(eq(true), eq("v2026"), eq(200), eq(true)))
                .thenReturn(new WosBigBangMigrationService.WosBigBangMigrationResult(
                        true,
                        "data/loaded",
                        "v2026",
                        Instant.now(),
                        Instant.now(),
                        new WosBigBangMigrationService.MigrationStepResult("ingest", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null),
                        new WosBigBangMigrationService.MigrationStepResult("facts", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                200, null, 0, false, 199),
                        new WosBigBangMigrationService.MigrationStepResult("enrichment", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null),
                        new WosBigBangMigrationService.MigrationStepResult("projections", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null),
                        new WosBigBangMigrationService.VerificationSummary(
                                0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, List.of(),
                                true, true, false, 0, 0, List.of(), List.of()
                        )
                ));

        mockMvc.perform(post("/admin/initialization/wos/runBigBangMigration")
                        .param("dryRun", "true")
                        .param("sourceVersion", "v2026")
                        .param("startBatchOverride", "200"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(rankingMaintenanceFacade).runWosBigBangMigration(true, "v2026", 200, true);
    }

    @Test
    void wosEnrichmentPageRendersTemplate() throws Exception {
        when(rankingMaintenanceFacade.latestWosCategoryRankingEnrichmentSummary())
                .thenReturn(new WosEnrichmentRunSummaryDto(
                        "enrich-category-rankings",
                        false,
                        null,
                        null,
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
                .thenReturn(new WosBigBangMigrationService.MigrationStepResult(
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
                .thenReturn(new WosBigBangMigrationService.MigrationStepResult(
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
    void runScopusBigBangRedirectsToInitializationPage() throws Exception {
        when(scopusBigBangMigrationService.runFull()).thenReturn(buildScopusResult());

        mockMvc.perform(post("/admin/initialization/scopus/runBigBang"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).runFull();
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
        when(scopusBigBangMigrationService.runCanonicalBuildStep(eq("citation"), eq(2), eq(true), eq(500), eq(false), eq(false))).thenReturn(result);

        mockMvc.perform(post("/admin/initialization/scopus/buildCanonical")
                        .param("entity", "citation")
                        .param("startBatchOverride", "2")
                        .param("chunkSizeOverride", "500")
                        .param("useCheckpoint", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).runCanonicalBuildStep("citation", 2, true, 500, false, false);
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
                        5, 5, 3, 4, 2, 5, 3, 4, 2, 6, 1, 7, 8
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

    private ScopusBigBangMigrationService.ScopusBigBangMigrationResult buildScopusResult() {
        return new ScopusBigBangMigrationService.ScopusBigBangMigrationResult(
                "data/scopus.json",
                Instant.now(),
                Instant.now(),
                new ScopusBigBangMigrationService.MigrationStepResult("ingest", true, 10, 5, 0, 5, 0, null, List.of(), null, null, null, null, null, null),
                new ScopusBigBangMigrationService.MigrationStepResult("build-facts", true, 10, 10, 0, 0, 0, null, List.of(), 0, 0, 1, 1, false, -1),
                new ScopusBigBangMigrationService.MigrationStepResult("build-projections", true, 10, 10, 0, 0, 0, null, List.of(), null, null, null, null, null, null),
                new ScopusBigBangMigrationService.IndexStepResult(true, 1, 0, 0, 0, List.of(), List.of()),
                new ScopusBigBangMigrationService.VerificationSummary(10, 5, 5, 5, 5, 1, 1, 1, 1, 1, 1, 5, 5)
        );
    }
}
