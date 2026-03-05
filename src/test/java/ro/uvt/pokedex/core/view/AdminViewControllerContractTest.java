package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.AdminInstitutionReportFacade;
import ro.uvt.pokedex.core.service.application.AdminScopusFacade;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsExportViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsViewModel;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminViewControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;
    @MockBean
    private ResearcherService researcherService;
    @MockBean
    private AdminCatalogFacade adminCatalogFacade;
    @MockBean
    private AdminScopusFacade adminScopusFacade;
    @MockBean
    private AdminInstitutionReportFacade adminInstitutionReportFacade;
    @MockBean
    private RankingMaintenanceFacade rankingMaintenanceFacade;

    @Test
    void computePositionsRedirectsAndDelegates() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/computePositionsForKnownQuarters"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/rankings/wos"));

        verify(rankingMaintenanceFacade).computePositionsForKnownQuarters();
    }

    @Test
    void computeMissingQuartersRedirectsAndDelegates() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/computeQuartersAndRankingsWhereMissing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/rankings/wos"));

        verify(rankingMaintenanceFacade).computeQuartersAndRankingsWhereMissing();
    }

    @Test
    void mergeDuplicateRankingsRedirectsAndDelegates() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/mergeDuplicateRankings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/rankings/wos"));

        verify(rankingMaintenanceFacade).mergeDuplicateRankings();
    }

    @Test
    void wosRankingsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/admin/rankings/wos"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/rankings"))
                .andExpect(model().attributeDoesNotExist("journals"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-wos-search\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-wos-sort\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-wos-direction\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-wos-size\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-wos-table-body\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-wos-prev\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-wos-next\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("/js/admin-rankings-wos.js")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void scopusForumsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/admin/scopus/forums"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scopus-venues"))
                .andExpect(model().attributeDoesNotExist("venues"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-search\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-sort\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-direction\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-size\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-table-body\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-prev\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-next\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("/js/admin-scopus-forums.js")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void scopusVenuesCompatibilityRoutesRedirectToForums() throws Exception {
        mockMvc.perform(get("/admin/scopus/venues"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/scopus/forums"));

        mockMvc.perform(get("/admin/scopus/venues/edit/{id}", "f1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/scopus/forums/edit/f1"));
    }

    @Test
    void scopusVenuesCompatibilityPostRouteDelegatesAndRedirectsToForumsEdit() throws Exception {
        mockMvc.perform(post("/admin/scopus/venues/edit/{id}", "f1")
                        .param("id", "f1")
                        .param("publicationName", "Forum One"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/scopus/forums/edit/f1"));

        verify(adminCatalogFacade, times(1)).saveScopusVenue(any(Forum.class));
    }

    @Test
    void scopusAuthorsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/admin/scopus/authors"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scopus-authors"))
                .andExpect(model().attributeDoesNotExist("authors"))
                .andExpect(model().attributeExists("defaultAfid"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-afid\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-search\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-sort\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-direction\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-size\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-table-body\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("/js/admin-scopus-authors.js")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void scopusAffiliationsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/admin/scopus/affiliations"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scopus-affiliations"))
                .andExpect(model().attributeDoesNotExist("affiliations"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-search\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-sort\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-direction\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-size\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-table-body\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("/js/admin-scopus-affiliations.js")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void editScopusAuthorPageRendersWithoutDatatablesScript() throws Exception {
        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");
        Publication publication = publication("p1", "f1", "2023-01-01");
        publication.setTitle("Paper One");
        publication.setAuthorCount(3);
        publication.setCitedbyCount(10);

        when(adminCatalogFacade.findScopusAuthorById("a1")).thenReturn(Optional.of(author));
        when(adminCatalogFacade.listPublicationsByAuthorId("a1")).thenReturn(List.of(publication));

        mockMvc.perform(get("/admin/scopus/authors/edit/{id}", "a1"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scopus-editAuthor"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void editScopusAffiliationPageStillRenders() throws Exception {
        Affiliation affiliation = new Affiliation();
        affiliation.setAfid("af1");
        affiliation.setName("Aff One");

        when(adminCatalogFacade.findScopusAffiliationById("af1")).thenReturn(Optional.of(affiliation));

        mockMvc.perform(get("/admin/scopus/affiliations/edit/{id}", "af1"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scopus-editAffiliations"));
    }

    @Test
    void createUserWithInvalidRoleRedirectsWithFlashErrorAndDoesNotCallService() throws Exception {
        mockMvc.perform(post("/admin/users/create")
                        .param("email", "bad@uvt.ro")
                        .param("password", "secret")
                        .param("roles", "INVALID_ROLE")
                        .with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attributeExists("errorMessage"));

        verify(userService, never()).createUser(eq("bad@uvt.ro"), eq("secret"), eq(List.of("INVALID_ROLE")));
    }

    @Test
    void createUserWithDuplicateEmailRedirectsWithFlashError() throws Exception {
        when(userService.areValidRoleNames(List.of("RESEARCHER"))).thenReturn(true);
        when(userService.createUser("existing@uvt.ro", "secret", List.of("RESEARCHER"))).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/users/create")
                        .param("email", "existing@uvt.ro")
                        .param("password", "secret")
                        .param("roles", "RESEARCHER")
                        .with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attributeExists("errorMessage"));
    }

    @Test
    void indicatorMutationsUsePostRoutes() throws Exception {
        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setName("Indicator One");
        Indicator duplicated = new Indicator();
        duplicated.setId("ind-2");
        duplicated.setName("Indicator One (copy)");
        when(adminCatalogFacade.duplicateIndicator("ind-1")).thenReturn(Optional.of(duplicated));

        mockMvc.perform(post("/admin/indicators/duplicate/{id}", "ind-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/indicators/edit/ind-2"));

        mockMvc.perform(post("/admin/indicators/delete/{id}", "ind-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/indicators"));

        verify(adminCatalogFacade).deleteIndicator("ind-1");
    }

    @Test
    void domainAndInstitutionDeleteUsePostRoutes() throws Exception {
        mockMvc.perform(post("/admin/domains/delete/{name}", "CS"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/domains"));

        mockMvc.perform(post("/admin/institutions/delete/{name}", "UVT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/institutions"));

        verify(adminCatalogFacade).deleteDomain("CS");
        verify(adminCatalogFacade).deleteInstitution("UVT");
    }

    @Test
    void oldIndicatorDeleteGetRouteIsNoLongerMapped() throws Exception {
        mockMvc.perform(get("/admin/indicators/delete/{id}", "ind-1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void institutionPublicationsViewRendersExpectedTemplateAndModel() throws Exception {
        Institution institution = new Institution();
        Publication publication = publication("p1", "f1", "2023-01-01");
        Author author = new Author();
        author.setId("a1");
        author.setName("Author A");
        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum A");
        IndividualReport report = new IndividualReport();
        report.setTitle("R1");

        AdminInstitutionPublicationsViewModel vm = new AdminInstitutionPublicationsViewModel(
                institution,
                List.of(publication),
                Map.of("a1", author),
                Map.of("f1", forum),
                new TreeMap<>(Map.of(2023, List.of(publication))),
                new TreeMap<>(Map.of(2023, 1L)),
                List.of(report)
        );

        when(adminInstitutionReportFacade.buildInstitutionPublicationsView(eq("i1")))
                .thenReturn(Optional.of(vm));

        mockMvc.perform(get("/admin/institutions/{id}/publications", "i1")
                        .with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/institution-publications"))
                .andExpect(model().attributeExists(
                        "authorMap",
                        "publicationsByYear",
                        "publicationsCountByYear",
                        "individualReports",
                        "forumMap",
                        "publications",
                        "institution"
                ));
    }

    @Test
    void institutionPublicationsViewRedirectsWhenInstitutionMissing() throws Exception {
        when(adminInstitutionReportFacade.buildInstitutionPublicationsView(eq("missing")))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/institutions/{id}/publications", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/institutions"));
    }

    @Test
    void institutionExportExcelReturnsWorkbookHeaders() throws Exception {
        Publication publication = publication("p1", "f1", "2023-01-01");
        publication.setEid("2-s2.0-123");
        publication.setDoi("10.1000/x");
        publication.setTitle("Paper");
        publication.setCitedbyCount(5);
        Author author = new Author();
        author.setId("a1");
        author.setName("Author A");
        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum A");

        Publication citing = publication("p2", "f1", "2024-01-01");
        citing.setEid("2-s2.0-999");
        citing.setDoi("10.1000/y");
        citing.setTitle("Citing Paper");
        citing.setCitedbyCount(2);

        AdminInstitutionPublicationsExportViewModel vm = new AdminInstitutionPublicationsExportViewModel(
                new Institution(),
                List.of(publication),
                Map.of("p1", List.of(citing)),
                Map.of("a1", author),
                Map.of("f1", forum)
        );

        when(adminInstitutionReportFacade.buildInstitutionPublicationsExport(eq("i1")))
                .thenReturn(Optional.of(vm));

        byte[] content = mockMvc.perform(get("/admin/institutions/{id}/publications/exportExcel", "i1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"institution_publications.xlsx\""))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var publications = workbook.getSheet("Publications");
            var citations = workbook.getSheet("Citations");
            org.junit.jupiter.api.Assertions.assertEquals("eID", publications.getRow(0).getCell(0).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Title", publications.getRow(0).getCell(2).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("2-s2.0-123", publications.getRow(1).getCell(0).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Paper", publications.getRow(1).getCell(2).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Author A", publications.getRow(1).getCell(4).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Forum A", publications.getRow(1).getCell(5).getStringCellValue());

            org.junit.jupiter.api.Assertions.assertEquals("Cited Publication eID", citations.getRow(0).getCell(0).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Citing Publication Title", citations.getRow(0).getCell(5).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("2-s2.0-123", citations.getRow(1).getCell(0).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Citing Paper", citations.getRow(1).getCell(5).getStringCellValue());
        }
    }

    @Test
    void indicatorsPageRendersExpectedTemplateAndFrontendModelContract() throws Exception {
        Activity activity = new Activity();
        activity.setId("act-1");
        activity.setName("Activity One");
        Activity.Field field = new Activity.Field();
        field.setName("duration");
        field.setNumber(true);
        activity.setFields(List.of(field));
        activity.setReferenceFields(List.of(Activity.ReferenceField.EVENT_NAME));

        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setName("Indicator One");

        Domain domain = new Domain();
        domain.setName("CS");

        when(adminCatalogFacade.listIndicators()).thenReturn(List.of(indicator));
        when(adminCatalogFacade.listActivities()).thenReturn(List.of(activity));
        when(adminCatalogFacade.listDomains()).thenReturn(List.of(domain));

        mockMvc.perform(get("/admin/indicators")
                        .with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/indicators"))
                .andExpect(model().attributeExists(
                        "indicators",
                        "activities",
                        "activityDescriptions",
                        "scoringStrategies",
                        "types",
                        "indicator",
                        "domains",
                        "selectors"
                ));
    }

    @Test
    void editIndicatorPageRendersExpectedTemplateAndModelContract() throws Exception {
        Activity activity = new Activity();
        activity.setId("act-1");
        activity.setName("Activity One");
        Activity.Field field = new Activity.Field();
        field.setName("duration");
        field.setNumber(true);
        activity.setFields(List.of(field));
        activity.setReferenceFields(List.of(Activity.ReferenceField.EVENT_NAME));

        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setName("Indicator One");

        Domain domain = new Domain();
        domain.setName("CS");

        when(adminCatalogFacade.findIndicatorById(eq("ind-1"))).thenReturn(Optional.of(indicator));
        when(adminCatalogFacade.listActivities()).thenReturn(List.of(activity));
        when(adminCatalogFacade.listDomains()).thenReturn(List.of(domain));

        mockMvc.perform(get("/admin/indicators/edit/{id}", "ind-1")
                        .with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/indicators-edit"))
                .andExpect(model().attributeExists(
                        "indicator",
                        "activities",
                        "activityDescriptions",
                        "scoringStrategies",
                        "types",
                        "domains",
                        "selectors"
                ));
    }

    private static Publication publication(String id, String forumId, String coverDate) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setForum(forumId);
        publication.setCoverDate(coverDate);
        publication.setAuthors(List.of("a1"));
        return publication;
    }

    private static User adminUser(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private RequestPostProcessor authenticatedUser(String email) {
        return request -> {
            User user = adminUser(email);
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "PLATFORM_ADMIN");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
