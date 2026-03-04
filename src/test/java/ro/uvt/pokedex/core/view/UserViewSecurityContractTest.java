package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserRankingFacade;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserViewController.class)
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class UserViewSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private UserService userService;
    @MockBean
    private ResearcherService researcherService;
    @MockBean
    private UserPublicationFacade userPublicationFacade;
    @MockBean
    private UserScopusTaskFacade userScopusTaskFacade;
    @MockBean
    private UserReportFacade userReportFacade;
    @MockBean
    private UserRankingFacade userRankingFacade;

    @Test
    void unauthenticatedUserPublicationsRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/user/publications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void unauthenticatedUserCnfisExportRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/user/publications/exportCNFIS2025"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

}
