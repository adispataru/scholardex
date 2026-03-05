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
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.UserRankingFacade;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorWorkbookExportViewModel;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportResult;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private UserPublicationFacade userPublicationFacade;
    @MockBean
    private UserScopusTaskFacade userScopusTaskFacade;
    @MockBean
    private UserReportFacade userReportFacade;
    @MockBean
    private UserRankingFacade userRankingFacade;
    @MockBean
    private UserIndicatorResultService userIndicatorResultService;
    @MockBean
    private UserIndividualReportRunService userIndividualReportRunService;

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
    void cnfis2025ExportRedirectsToLoginWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/user/publications/exportCNFIS2025"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void publicationsPageRedirectsToLoginWhenAuthenticationMissing() throws Exception {
        mockMvc.perform(get("/user/publications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
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
    void cnfis2025ExportWithInvalidStartYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/user/publications/exportCNFIS2025")
                        .param("start", "bad")
                        .param("end", "2024")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cnfis2025ExportWithInvalidEndYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/user/publications/exportCNFIS2025")
                        .param("start", "2021")
                        .param("end", "bad")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cnfis2025ExportWithStartGreaterThanEndReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/user/publications/exportCNFIS2025")
                        .param("start", "2024")
                        .param("end", "2021")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cnfis2025ExportWithOutOfRangeYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/user/publications/exportCNFIS2025")
                        .param("start", "1899")
                        .param("end", "2024")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isBadRequest());
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

    @Test
    void rankingPageRedirectsToNewSharedRankingsRoute() throws Exception {
        mockMvc.perform(get("/user/rankings/{id}", "forum-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings/wos/forum-1"));
    }

    @Test
    void indicatorApplyUsesPersistedResultPayload() throws Exception {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setName("Indicator 1");
        indicator.setDomain(domain);
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);
        indicator.setFormula("S");

        when(userIndicatorResultService.getOrCreateLatest(eq("u@uvt.ro"), eq("ind-1")))
                .thenReturn(new IndicatorApplyResultDto(
                        "r1",
                        "ind-1",
                        "user/indicators-apply-publications",
                        Map.of("indicator", indicator, "total", "1.00", "publications", List.of(), "scores", Map.of(), "forumMap", Map.of(), "allQuarters", List.of(), "allValues", List.of()),
                        new IndicatorApplyResultDto.Summary(1.0, null, List.of(), List.of()),
                        IndicatorApplyResultDto.Source.PERSISTED,
                        null,
                        null,
                        0
                ));

        mockMvc.perform(get("/user/indicators/apply/{id}", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/indicators-apply-publications"))
                .andExpect(model().attributeExists("indicator", "total", "resultMetaSource", "resultMetaRefreshVersion"));
    }

    @Test
    void indicatorApplyRefreshRedirectsToApplyPage() throws Exception {
        mockMvc.perform(post("/user/indicators/apply/{id}/refresh", "ind-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/indicators/apply/ind-1"));
    }

    @Test
    void individualReportViewDisplaysCriterionNameOrFallback() throws Exception {
        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setTitle("My Report");
        report.setDescription("Desc");
        report.setIndicators(List.of());

        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion named = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        named.setName("Research Impact");
        named.setIndicatorIndices(new ArrayList<>());
        named.setThresholds(new ArrayList<>());

        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion unnamed = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        unnamed.setName("  ");
        unnamed.setIndicatorIndices(new ArrayList<>());
        unnamed.setThresholds(new ArrayList<>());

        report.setCriteria(List.of(named, unnamed));

        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(new IndividualReportRunDto(
                        "run-1",
                        "rep-1",
                        List.of(),
                        Map.of(),
                        Map.of(0, 1.0, 1, 2.0),
                        Instant.parse("2026-03-05T10:00:00Z"),
                        IndividualReportRunDto.Source.PERSISTED
                )));

        String html = mockMvc.perform(get("/user/individualReports/view/{id}", "rep-1")
                        .with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individualReport-view"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Research Impact"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Criterion 2"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("Total Score for All Indicators"));
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
