package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
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
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(UserViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
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

    @Test
    void publicationsPageRendersExpectedTemplateAndFrontendModelContract() throws Exception {
        Publication publication = new Publication();
        publication.setId("p1");
        publication.setForum("f1");
        publication.setAuthors(List.of("a1"));

        Author author = new Author();
        author.setId("a1");
        author.setName("Author A");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum A");

        when(userPublicationFacade.buildUserPublicationsView(eq("r1")))
                .thenReturn(Optional.of(new UserPublicationsViewModel(
                        List.of(publication),
                        3,
                        Map.of("a1", author),
                        Map.of("f1", forum),
                        8
                )));

        User user = userPrincipal("u@uvt.ro");
        user.setResearcherId("r1");

        mockMvc.perform(get("/user/publications").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("user/publications"))
                .andExpect(model().attributeExists("publications", "hIndex", "authorMap", "forumMap", "numCitations", "user"));
    }

    @Test
    void editPublicationFormRendersPublicationEditTemplateWhenPublicationExists() throws Exception {
        Publication publication = new Publication();
        publication.setEid("eid-1");
        when(userPublicationFacade.findPublicationForEdit(eq("eid-1")))
                .thenReturn(Optional.of(publication));

        mockMvc.perform(get("/user/publications/edit/{eid}", "eid-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/publications-edit"))
                .andExpect(model().attributeExists("publication"));
    }

    private User userPrincipal(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private RequestPostProcessor authenticatedUser(String email) {
        return authenticatedUser(userPrincipal(email));
    }

    private RequestPostProcessor authenticatedUser(User user) {
        return request -> {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "RESEARCHER");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
