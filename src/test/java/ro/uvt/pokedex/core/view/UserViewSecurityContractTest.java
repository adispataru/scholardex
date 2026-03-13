package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserViewController.class)
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class UserViewSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ResearcherService researcherService;
    @MockitoBean
    private UserPublicationFacade userPublicationFacade;
    @MockitoBean
    private UserScopusTaskFacade userScopusTaskFacade;
    @MockitoBean
    private UserReportFacade userReportFacade;
    @MockitoBean
    private UserIndicatorResultService userIndicatorResultService;
    @MockitoBean
    private UserIndividualReportRunService userIndividualReportRunService;

    @Test
    void unauthenticatedUserDashboardRouteRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/user/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void unauthenticatedUserRootCompatibilityRouteRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/user"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void unauthenticatedUserPublicationsRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/user/publications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void unauthenticatedUserCnfisExportRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/user/exports/cnfis"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void unauthenticatedUserIndividualReportsRedirectToLogin() throws Exception {
        mockMvc.perform(get("/user/individual-reports"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void unauthenticatedUserScopusTasksRedirectToLogin() throws Exception {
        mockMvc.perform(get("/user/publications/scopus-tasks"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void authenticatedUserStillGetsNotFoundOnRemovedLegacyAliases() throws Exception {
        mockMvc.perform(get("/user/individualReports")
                        .with(user("u@uvt.ro").authorities(() -> "RESEARCHER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/user/publications/exportCNFIS2025")
                        .with(user("u@uvt.ro").authorities(() -> "RESEARCHER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/user/export/cnfis")
                        .with(user("u@uvt.ro").authorities(() -> "RESEARCHER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/user/publications/scopus_tasks")
                        .with(user("u@uvt.ro").authorities(() -> "RESEARCHER")))
                .andExpect(status().isNotFound());
    }

}
