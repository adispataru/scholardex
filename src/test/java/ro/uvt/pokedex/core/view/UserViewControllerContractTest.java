package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorWorkbookExportViewModel;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportResult;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserViewController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserViewControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;
    @MockBean
    private ResearcherService researcherService;
    @MockBean
    private ScopusAuthorRepository scopusAuthorRepository;
    @MockBean
    private ScopusCitationRepository scopusCitationRepository;
    @MockBean
    private ScopusPublicationRepository scopusPublicationRepository;
    @MockBean
    private ScopusForumRepository scopusVenueRepository;
    @MockBean
    private RankingRepository rankingRepository;
    @MockBean
    private DomainRepository domainRepository;
    @MockBean
    private UserPublicationFacade userPublicationFacade;
    @MockBean
    private UserScopusTaskFacade userScopusTaskFacade;
    @MockBean
    private UserReportFacade userReportFacade;

    @Test
    void indicatorExportRedirectsToLoginWhenAuthenticationMissing() throws Exception {
        mockMvc.perform(get("/user/indicators/export/{id}", "ind-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void indicatorExportReturnsNotFoundWhenWorkbookMissing() throws Exception {
        when(userReportFacade.buildIndicatorWorkbookExport(eq("u@uvt.ro"), eq("ind-1")))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/user/indicators/export/{id}", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void indicatorExportReturnsWorkbookResponseContract() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        when(userReportFacade.buildIndicatorWorkbookExport(eq("u@uvt.ro"), eq("ind-1")))
                .thenReturn(Optional.of(new UserIndicatorWorkbookExportViewModel(
                        bytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "indicator_results.xlsx"
                )));

        mockMvc.perform(get("/user/indicators/export/{id}", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"indicator_results.xlsx\""))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void cnfis2025ExportReturnsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/user/publications/exportCNFIS2025"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cnfis2025ExportReturnsWorkbookResponseContract() throws Exception {
        byte[] bytes = new byte[]{9, 8, 7};
        when(userReportFacade.buildUserCnfisWorkbookExport(eq("u@uvt.ro"), eq(2021), eq(2024)))
                .thenReturn(UserWorkbookExportResult.ok(
                        bytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx"
                ));

        mockMvc.perform(get("/user/publications/exportCNFIS2025")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx\""))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(bytes));
    }

    private User userPrincipal(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private RequestPostProcessor authenticatedUser(String email) {
        return request -> {
            User user = userPrincipal(email);
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "RESEARCHER");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
