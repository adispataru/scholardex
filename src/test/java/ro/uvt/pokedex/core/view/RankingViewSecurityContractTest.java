package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.AdminInstitutionReportFacade;
import ro.uvt.pokedex.core.service.application.AdminScopusFacade;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.WosRankingDetailsReadService;

import java.util.Collections;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RankingViewController.class, AdminViewController.class})
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class RankingViewSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private CacheService cacheService;
    @MockitoBean
    private AdminCatalogFacade adminCatalogFacade;
    @MockitoBean
    private UrapRankingFacade urapRankingFacade;
    @MockitoBean
    private WosRankingDetailsReadService wosRankingDetailsReadService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ResearcherService researcherService;
    @MockitoBean
    private AdminScopusFacade adminScopusFacade;
    @MockitoBean
    private AdminInstitutionReportFacade adminInstitutionReportFacade;
    @MockitoBean
    private RankingMaintenanceFacade rankingMaintenanceFacade;

    @BeforeEach
    void setupDefaults() {
        when(adminCatalogFacade.listCoreRankings()).thenReturn(Collections.emptyList());
        when(urapRankingFacade.listRankings()).thenReturn(Collections.emptyList());
    }

    @Test
    void unauthenticatedRankingsWosRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/rankings/wos"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void researcherCanAccessWosRankings() throws Exception {
        mockMvc.perform(get("/rankings/wos")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk());
    }

    @Test
    void supervisorCanAccessCoreRankings() throws Exception {
        mockMvc.perform(get("/rankings/core")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanAccessUrapRankings() throws Exception {
        mockMvc.perform(get("/rankings/urap")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void nonAdminCannotInvokeAdminRankingOperations() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/mergeDuplicateRankings")
                        .with(csrf())
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotInvokeProjectionRebuildOperation() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/rebuildProjections")
                        .with(csrf())
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotInvokeEnsureIndexesOperation() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/ensureIndexes")
                        .with(csrf())
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotInvokeBigBangMigrationOperation() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/runBigBangMigration")
                        .with(csrf())
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }
}
