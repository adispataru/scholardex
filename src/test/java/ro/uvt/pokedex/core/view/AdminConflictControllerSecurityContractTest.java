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
import ro.uvt.pokedex.core.service.application.ConflictOperationsFacade;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminConflictController.class)
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class AdminConflictControllerSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private CacheService cacheService;
    @MockitoBean
    private ConflictOperationsFacade conflictOperationsFacade;

    @Test
    void nonAdminCannotAccessConflictsPage() throws Exception {
        mockMvc.perform(get("/admin/conflicts")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotRunConflictMutations() throws Exception {
        mockMvc.perform(post("/admin/conflicts/resolve")
                        .param("id", "c1")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/conflicts/dismiss")
                        .param("id", "c1")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/conflicts/bulkStatus")
                        .param("ids", "c1", "c2")
                        .param("action", "resolve")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/conflicts/identity/open/clear")
                        .param("confirmation", "RESET")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/conflicts/wos/identity/clear")
                        .param("confirmation", "RESET")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/conflicts/wos/fact/clear")
                        .param("confirmation", "RESET")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(post("/admin/conflicts/scopus/link/clear")
                        .param("confirmation", "RESET")
                        .with(csrf())
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }
}
