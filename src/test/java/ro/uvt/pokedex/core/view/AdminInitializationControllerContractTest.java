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
import ro.uvt.pokedex.core.service.application.WosBigBangMigrationService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .andExpect(view().name("admin/initialization"));
    }

    @Test
    void runWosBigBangRedirectsToInitializationPage() throws Exception {
        when(rankingMaintenanceFacade.runWosBigBangMigration(eq(true), eq("v2026")))
                .thenReturn(new WosBigBangMigrationService.WosBigBangMigrationResult(
                        true,
                        "data/loaded",
                        "v2026",
                        Instant.now(),
                        Instant.now(),
                        new WosBigBangMigrationService.MigrationStepResult("ingest", false, 0, 0, 0, 0, 0, "dry-run", List.of()),
                        new WosBigBangMigrationService.MigrationStepResult("facts", false, 0, 0, 0, 0, 0, "dry-run", List.of()),
                        new WosBigBangMigrationService.MigrationStepResult("projections", false, 0, 0, 0, 0, 0, "dry-run", List.of()),
                        new WosBigBangMigrationService.VerificationSummary(
                                0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, List.of(),
                                true, true, false, 0, 0, List.of(), List.of()
                        )
                ));

        mockMvc.perform(post("/admin/initialization/wos/runBigBangMigration")
                        .param("dryRun", "true")
                        .param("sourceVersion", "v2026"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(rankingMaintenanceFacade).runWosBigBangMigration(true, "v2026");
    }

    @Test
    void runScopusBigBangRedirectsToInitializationPage() throws Exception {
        when(scopusBigBangMigrationService.runFull()).thenReturn(buildScopusResult());

        mockMvc.perform(post("/admin/initialization/scopus/runBigBang"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"));

        verify(scopusBigBangMigrationService).runFull();
    }

    private ScopusBigBangMigrationService.ScopusBigBangMigrationResult buildScopusResult() {
        return new ScopusBigBangMigrationService.ScopusBigBangMigrationResult(
                "data/scopus.json",
                Instant.now(),
                Instant.now(),
                new ScopusBigBangMigrationService.MigrationStepResult("ingest", true, 10, 5, 0, 5, 0, null, List.of()),
                new ScopusBigBangMigrationService.MigrationStepResult("build-facts", true, 10, 10, 0, 0, 0, null, List.of()),
                new ScopusBigBangMigrationService.MigrationStepResult("build-projections", true, 10, 10, 0, 0, 0, null, List.of()),
                new ScopusBigBangMigrationService.IndexStepResult(true, 1, 0, 0, 0, List.of(), List.of()),
                new ScopusBigBangMigrationService.VerificationSummary(10, 5, 5, 1, 1, 1, 1, 1, 1, 5)
        );
    }
}
