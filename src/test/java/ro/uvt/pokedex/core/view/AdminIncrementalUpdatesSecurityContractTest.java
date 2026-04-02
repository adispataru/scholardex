package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminIncrementalUpdatesController.class)
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class AdminIncrementalUpdatesSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private CacheService cacheService;
    @MockitoBean
    private IncrementalUpdateUploadFacade incrementalUpdateUploadFacade;

    @Test
    void nonAdminCannotAccessIncrementalUpdatesPage() throws Exception {
        mockMvc.perform(get("/admin/incremental-updates")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotUploadWosOrScopusIncrementalFiles() throws Exception {
        MockMultipartFile wosFile = new MockMultipartFile("file", "wos.json", "application/json", "{}".getBytes());
        MockMultipartFile scopusFile = new MockMultipartFile("file", "scopus.json", "application/json", "{}".getBytes());

        mockMvc.perform(multipart("/admin/incremental-updates/wos")
                        .file(wosFile)
                        .param("sourceType", "OFFICIAL_JSON")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(multipart("/admin/incremental-updates/scopus")
                        .file(scopusFile)
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunScopusPostUploadMaintenance() throws Exception {
        mockMvc.perform(post("/admin/incremental-updates/wos/enrichCategoryRankings")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/incremental-updates/wos/rebuildProjections")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/incremental-updates/scopus/buildProjections")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/incremental-updates/scopus/reconcileEdges")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/incremental-updates/scopus/reconcileSourceLinks")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }
}
