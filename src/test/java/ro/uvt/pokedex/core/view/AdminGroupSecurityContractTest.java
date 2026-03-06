package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.application.GroupCnfisExportFacade;
import ro.uvt.pokedex.core.service.application.GroupExportFacade;
import ro.uvt.pokedex.core.service.application.GroupManagementFacade;
import ro.uvt.pokedex.core.service.application.GroupReportFacade;
import ro.uvt.pokedex.core.service.importing.GroupService;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminGroupController.class)
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class AdminGroupSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private GroupManagementFacade groupManagementFacade;
    @MockitoBean
    private GroupReportFacade groupReportFacade;
    @MockitoBean
    private GroupExportFacade groupExportFacade;
    @MockitoBean
    private GroupCnfisExportFacade groupCnfisExportFacade;
    @MockitoBean
    private GroupService groupService;

    @Test
    void nonAdminUserCannotAccessAdminGroupsAndGetsMvcDeniedRedirect() throws Exception {
        mockMvc.perform(get("/admin/groups")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }
}
