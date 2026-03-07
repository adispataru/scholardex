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

    @Test
    void nonAdminCannotAccessInitializationPage() throws Exception {
        mockMvc.perform(get("/admin/initialization")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunScopusBigBang() throws Exception {
        mockMvc.perform(post("/admin/initialization/scopus/runBigBang")
                        .with(csrf())
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
}
