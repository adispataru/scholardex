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
import ro.uvt.pokedex.core.service.application.UserDefinedTriageFacade;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserDefinedTriageController.class)
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class AdminUserDefinedTriageControllerSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private CacheService cacheService;
    @MockitoBean
    private UserDefinedTriageFacade userDefinedTriageFacade;

    @Test
    void nonAdminCannotAccessUserDefinedTriagePage() throws Exception {
        mockMvc.perform(get("/admin/user-defined-triage")
                        .with(user("researcher@uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }
}
