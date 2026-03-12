package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.application.GeneralInitializationService;
import ro.uvt.pokedex.core.service.application.DualReadGateService;
import ro.uvt.pokedex.core.service.application.H22OperationalStatusService;
import ro.uvt.pokedex.core.service.application.PostgresMaterializedViewRefreshService;
import ro.uvt.pokedex.core.service.application.PostgresReportingProjectionService;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.ScopusBigBangMigrationService;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminInitializationController.class)
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class AdminInitializationSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private CacheService cacheService;
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
    private DualReadGateService dualReadGateService;
    @MockitoBean
    private H22OperationalStatusService h22OperationalStatusService;

    @Test
    void nonAdminCannotAccessInitializationPage() throws Exception {
        mockMvc.perform(get("/admin/initialization")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotResetScopusCanonicalState() throws Exception {
        mockMvc.perform(post("/admin/initialization/scopus/resetCanonicalState")
                        .with(csrf())
                        .param("confirmation", "RESET")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunScopusCitationBackfill() throws Exception {
        mockMvc.perform(post("/admin/initialization/scopus/backfillCanonicalCitations")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunScopusCanonicalBuild() throws Exception {
        mockMvc.perform(post("/admin/initialization/scopus/buildCanonical")
                        .with(csrf())
                        .param("entity", "all")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotResetScopusCanonicalCheckpoints() throws Exception {
        mockMvc.perform(post("/admin/initialization/scopus/resetCanonicalCheckpoints")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunTouchQueueMaintenance() throws Exception {
        mockMvc.perform(post("/admin/initialization/scopus/showTouchQueueBacklog")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/scopus/rebuildTouchQueuesFromEvents")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/scopus/drainTouchQueues")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunPostgresProjectionMaintenance() throws Exception {
        mockMvc.perform(post("/admin/initialization/postgres/projection/runFull")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/postgres/projection/runIncremental")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/postgres/projection/showStatus")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/postgres/projection/resetState")
                        .with(csrf())
                        .param("confirmation", "RESET")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunPostgresMaterializedViewMaintenance() throws Exception {
        mockMvc.perform(post("/admin/initialization/postgres/materialized/refreshAll")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/postgres/materialized/refreshSlice")
                        .with(csrf())
                        .param("slice", "wos")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/postgres/materialized/showStatus")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(get("/admin/initialization/postgres/materialized/status")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunPostgresDualReadGateMaintenance() throws Exception {
        mockMvc.perform(post("/admin/initialization/postgres/dualReadGate/run")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/postgres/dualReadGate/showStatus")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(get("/admin/initialization/postgres/dualReadGate/status")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotReadPostgresOperationalStatus() throws Exception {
        mockMvc.perform(post("/admin/initialization/postgres/operational/showStatus")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(get("/admin/initialization/postgres/operational/status")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunWosEnrichmentApi() throws Exception {
        mockMvc.perform(post("/admin/initialization/wos/enrichment/run")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotAccessWosEnrichmentPage() throws Exception {
        mockMvc.perform(get("/admin/initialization/wos/enrichment")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunWosEnrichmentPageFlow() throws Exception {
        mockMvc.perform(post("/admin/initialization/wos/enrichment/runPage")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotReadWosEnrichmentSummaryApi() throws Exception {
        mockMvc.perform(get("/admin/initialization/wos/enrichment/summary")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunGeneralInitializationAll() throws Exception {
        mockMvc.perform(post("/admin/initialization/general/runAll")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunGeneralInitializationSteps() throws Exception {
        mockMvc.perform(post("/admin/initialization/general/adminUser")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/general/domain")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/general/artisticEvents")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/general/urap")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/general/cncsis")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/general/coreConference")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/admin/initialization/general/sense")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }
}
