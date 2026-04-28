package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.AdminInstitutionReportFacade;
import ro.uvt.pokedex.core.service.application.AdminDashboardService;
import ro.uvt.pokedex.core.service.application.GroupManagementFacade;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.WosBigBangMigrationService;
import ro.uvt.pokedex.core.service.application.WosRankingDetailsReadService;
import ro.uvt.pokedex.core.service.application.model.AdminDashboardViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupListViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminOperationStatus;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsExportViewModel;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private AdminCatalogFacade adminCatalogFacade;
    @MockitoBean
    private AdminInstitutionReportFacade adminInstitutionReportFacade;
    @MockitoBean
    private RankingMaintenanceFacade rankingMaintenanceFacade;
    @MockitoBean
    private WosRankingDetailsReadService wosRankingDetailsReadService;
    @MockitoBean
    private AdminDashboardService adminDashboardService;
    @MockitoBean
    private GroupManagementFacade groupManagementFacade;

    @Test
    void sharedNavbarRendersPostLogoutControl() throws Exception {
        when(adminDashboardService.buildDashboard()).thenReturn(new AdminDashboardViewModel(
                0,
                0,
                0,
                0,
                0,
                AdminOperationStatus.neverRun(),
                AdminOperationStatus.neverRun(),
                AdminOperationStatus.neverRun(),
                List.of(),
                List.of()
        ));

        mockMvc.perform(get("/admin").with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("class=\"app-shell-logout\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("action=\"/logout\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("method=\"post\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Log out")));
    }

    @Test
    void rebuildWosProjectionsRedirectsAndDelegates() throws Exception {
        when(rankingMaintenanceFacade.rebuildWosProjections()).thenReturn(new ImportProcessingResult(10));

        mockMvc.perform(post("/admin/rankings/wos/rebuildProjections"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forums?wos=indexed"));

        verify(rankingMaintenanceFacade).rebuildWosProjections();
    }

    @Test
    void ensureWosIndexesRedirectsAndDelegates() throws Exception {
        when(rankingMaintenanceFacade.ensureWosIndexes()).thenReturn(
                new ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService.WosIndexEnsureResult(
                        List.of("idx"), List.of("idx2"), List.of(), List.of()
                )
        );

        mockMvc.perform(post("/admin/rankings/wos/ensureIndexes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forums?wos=indexed"));

        verify(rankingMaintenanceFacade).ensureWosIndexes();
    }

    @Test
    void runWosBigBangMigrationRedirectsAndDelegates() throws Exception {
        when(rankingMaintenanceFacade.runWosBigBangMigration(eq(true), eq("v2026")))
                .thenReturn(new WosBigBangMigrationService.WosBigBangMigrationResult(
                        true,
                        "data/loaded",
                        "v2026",
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        new MigrationStepResult("ingest", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null, null),
                        new MigrationStepResult("facts", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null, null),
                        new MigrationStepResult("enrichment", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null, null),
                        new MigrationStepResult("projections", false, 0, 0, 0, 0, 0, "dry-run", List.of(),
                                null, null, null, null, null, null),
                        new WosBigBangMigrationService.VerificationSummary(
                                0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, List.of(),
                                true, true,
                                false, 0, 0, List.of(), List.of("eligibility")
                        )
                ));

        mockMvc.perform(post("/admin/rankings/wos/runBigBangMigration")
                        .param("dryRun", "true")
                        .param("sourceVersion", "v2026"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forums?wos=indexed"));

        verify(rankingMaintenanceFacade).runWosBigBangMigration(true, "v2026");
    }

    @Test
    void removedAdminWosReadRouteReturnsNotFound() throws Exception {
        mockMvc.perform(get("/admin/rankings/wos"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removedAdminWosDetailReadRouteReturnsNotFound() throws Exception {
        mockMvc.perform(get("/admin/rankings/wos/{id}", "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removedAdminCoreReadRouteReturnsNotFound() throws Exception {
        mockMvc.perform(get("/admin/rankings/core"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removedAdminCoreDetailReadRouteReturnsNotFound() throws Exception {
        mockMvc.perform(get("/admin/rankings/core/{id}", "c1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removedAdminEventsReadRouteReturnsNotFound() throws Exception {
        mockMvc.perform(get("/admin/rankings/events"))
                .andExpect(status().isNotFound());
    }

    @Test
    void usersPageRendersSharedStatCardGrid() throws Exception {
        when(userService.getAllUsers()).thenReturn(List.of());
        when(groupManagementFacade.buildGroupListView())
                .thenReturn(new GroupListViewModel(List.of(), List.of(), List.of(), List.of(), null));

        mockMvc.perform(get("/admin/users").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"))
                .andExpect(model().attributeExists("statCards"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("app-summary-card--primary")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("All registered accounts on the platform.")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("id=\"editUserModal\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("id=\"assignGroupModal\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("data-app-modal-shell")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("aria-labelledby=\"editUserModalLabel\"")));
    }

    @Test
    void scholardexForumsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/admin/scholardex/forums"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scholardex-forums"))
                .andExpect(model().attributeDoesNotExist("venues"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-search\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-sort\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-direction\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-wos\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-size\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-table-body\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-prev\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-forums-next\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("Actions")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"editForumModal\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("data-app-modal-shell")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("aria-labelledby=\"editForumModalLabel\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("/js/admin-scholardex-forums.js")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void removedAdminScopusCompatibilityRoutesReturnNotFound() throws Exception {
        mockMvc.perform(get("/admin/scopus/venues"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/forums"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/venues/edit/{id}", "f1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/forums/edit/{id}", "f1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/admin/scopus/venues/edit/{id}", "f1")
                        .param("id", "f1")
                        .param("publicationName", "Forum One"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/authors"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/authors/edit/{id}", "a1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/admin/scopus/authors/edit/{id}", "a1")
                        .param("id", "a1")
                        .param("name", "Author One"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/affiliations"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/affiliations/edit/{id}", "af1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/admin/scopus/affiliations/edit/{id}", "af1")
                        .param("id", "af1")
                        .param("name", "Aff One"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/publications"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/publications/citations")
                        .param("id", "p1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/scopus/publications/search")
                        .param("authorName", "A")
                        .param("paperTitle", "T"))
                .andExpect(status().isNotFound());
    }

    @Test
    void scholardexAuthorsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/admin/scholardex/authors"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scholardex-authors"))
                .andExpect(model().attributeDoesNotExist("authors"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-search\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-sort\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-direction\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-size\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-authors-table-body\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("/js/admin-scholardex-authors.js")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));

    }

    @Test
    void scholardexAffiliationsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/admin/scholardex/affiliations"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scholardex-affiliations"))
                .andExpect(model().attributeDoesNotExist("affiliations"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-search\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-sort\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-direction\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-size\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"admin-affiliations-table-body\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"editAffiliationModal\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("data-app-modal-shell")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("aria-labelledby=\"editAffiliationModalLabel\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("/js/admin-scholardex-affiliations.js")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));

    }

    @Test
    void removedScholardexAuthorEditRoutesRedirectToAuthorsList() throws Exception {
        mockMvc.perform(get("/admin/scholardex/authors/edit/{id}", "a1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/scholardex/authors"));

        mockMvc.perform(post("/admin/scholardex/authors/edit/{id}", "a1")
                        .param("id", "a1")
                        .param("name", "Author One"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/scholardex/authors"));

        verify(adminCatalogFacade, never()).saveScopusAuthor(any(ScholardexAuthorView.class));
    }

    @Test
    void scholardexCitationsTemplateLinksAuthorsToCanonicalDetailView() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/admin/scholardex-citations.html"));

        org.junit.jupiter.api.Assertions.assertTrue(template.contains("/user/authors/view/{id}"));
        org.junit.jupiter.api.Assertions.assertFalse(template.contains("/admin/scholardex/authors/edit/{id}"));
    }

    @Test
    void adminAuthorsScriptLinksRowsToCanonicalDetailView() throws Exception {
        String script = Files.readString(Path.of("src/main/resources/static/js/admin-scholardex-authors.js"));

        org.junit.jupiter.api.Assertions.assertTrue(script.contains("/user/authors/view/"));
        org.junit.jupiter.api.Assertions.assertFalse(script.contains("/admin/scholardex/authors/edit/"));
    }

    @Test
    void scholardexAffiliationEditPageRedirectsAndEditDataReturnsJson() throws Exception {
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setAfid("af1");
        affiliation.setName("Aff One");
        affiliation.setCity("Timisoara");
        affiliation.setCountry("Romania");

        when(adminCatalogFacade.findScopusAffiliationById("af1")).thenReturn(Optional.of(affiliation));

        mockMvc.perform(get("/admin/scholardex/affiliations/edit/{id}", "af1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/scholardex/affiliations"));

        mockMvc.perform(get("/admin/scholardex/affiliations/{id}/edit-data", "af1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Aff One"))
                .andExpect(jsonPath("$.city").value("Timisoara"))
                .andExpect(jsonPath("$.country").value("Romania"));
    }

    @Test
    void scholardexForumEditPageRedirectsAndEditDataReturnsJson() throws Exception {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum One");
        forum.setIssn("1234-5678");
        forum.setAggregationType("Journal");

        when(adminCatalogFacade.findScopusVenueById("f1")).thenReturn(Optional.of(forum));

        mockMvc.perform(get("/admin/scholardex/forums/edit/{id}", "f1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/scholardex/forums"));

        mockMvc.perform(get("/admin/scholardex/forums/{id}/edit-data", "f1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("f1"))
                .andExpect(jsonPath("$.publicationName").value("Forum One"))
                .andExpect(jsonPath("$.issn").value("1234-5678"))
                .andExpect(jsonPath("$.aggregationType").value("Journal"));
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
    void shortEditModalSaveEndpointsDelegateAndRedirectToCanonicalSurfaces() throws Exception {
        mockMvc.perform(post("/admin/scholardex/forums/edit/{id}", "f1")
                        .param("publicationName", "Forum One")
                        .param("issn", "1234-5678"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/scholardex/forums"));

        ArgumentCaptor<ScholardexForumView> forumCaptor = ArgumentCaptor.forClass(ScholardexForumView.class);
        verify(adminCatalogFacade).saveScopusVenue(forumCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("f1", forumCaptor.getValue().getId());
        org.junit.jupiter.api.Assertions.assertEquals("Forum One", forumCaptor.getValue().getPublicationName());

        mockMvc.perform(post("/admin/scholardex/affiliations/edit/{id}", "af1")
                        .param("name", "Aff One")
                        .param("city", "Timisoara")
                        .param("country", "Romania"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/scholardex/affiliations"));

        ArgumentCaptor<ScholardexAffiliationView> affiliationCaptor = ArgumentCaptor.forClass(ScholardexAffiliationView.class);
        verify(adminCatalogFacade).saveScopusAffiliation(affiliationCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("af1", affiliationCaptor.getValue().getAfid());
        org.junit.jupiter.api.Assertions.assertEquals("Aff One", affiliationCaptor.getValue().getName());

        mockMvc.perform(post("/admin/domains/update")
                        .param("name", "CS")
                        .param("description", "Computer Science")
                        .param("wosCategories[0]", "COMPUTER SCIENCE - SCIE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/domains"));

        ArgumentCaptor<Domain> domainCaptor = ArgumentCaptor.forClass(Domain.class);
        verify(adminCatalogFacade).saveDomain(domainCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("CS", domainCaptor.getValue().getName());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("COMPUTER SCIENCE - SCIE"), domainCaptor.getValue().getWosCategories());

        mockMvc.perform(post("/admin/institutions/update")
                        .param("name", "UVT")
                        .param("description", "University")
                        .param("scopusAffiliations[0].afid", "af1")
                        .param("wosAffiliations[0]", "West University"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/institutions"));

        ArgumentCaptor<Institution> institutionCaptor = ArgumentCaptor.forClass(Institution.class);
        verify(adminCatalogFacade).saveInstitution(institutionCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("UVT", institutionCaptor.getValue().getName());
        org.junit.jupiter.api.Assertions.assertEquals("af1", institutionCaptor.getValue().getScopusAffiliations().getFirst().getAfid());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("West University"), institutionCaptor.getValue().getWosAffiliations());
    }

    @Test
    void domainsPageRendersSharedEditModal() throws Exception {
        Domain domain = new Domain();
        domain.setName("CS");
        domain.setDescription("Computer Science");
        domain.setWosCategories(List.of("COMPUTER SCIENCE - SCIE"));

        when(adminCatalogFacade.listDomains()).thenReturn(List.of(domain));
        when(adminCatalogFacade.listWosCategories()).thenReturn(List.of("COMPUTER SCIENCE - SCIE"));

        mockMvc.perform(get("/admin/domains"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/domains"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"editDomainModal\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("data-app-modal-shell")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("aria-labelledby=\"editDomainModalLabel\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("data-edit-domain-id=\"CS\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"edit-domain-form\"")));
    }

    @Test
    void domainEditPageRedirectsAndEditDataReturnsJson() throws Exception {
        Domain domain = new Domain();
        domain.setName("CS");
        domain.setDescription("Computer Science");
        domain.setWosCategories(List.of("COMPUTER SCIENCE - SCIE"));

        when(adminCatalogFacade.findDomainById("CS")).thenReturn(Optional.of(domain));

        mockMvc.perform(get("/admin/domains/edit/{id}", "CS"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/domains"));

        mockMvc.perform(get("/admin/domains/{id}/edit-data", "CS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CS"))
                .andExpect(jsonPath("$.description").value("Computer Science"))
                .andExpect(jsonPath("$.wosCategories[0]").value("COMPUTER SCIENCE - SCIE"));
    }

    @Test
    void institutionsPageRendersSharedEditModal() throws Exception {
        Institution institution = new Institution();
        institution.setName("UVT");
        institution.setDescription("University");
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setAfid("af1");
        affiliation.setName("Aff One");
        institution.setScopusAffiliations(List.of(affiliation));
        institution.setWosAffiliations(List.of("West University"));

        when(adminCatalogFacade.listInstitutions()).thenReturn(List.of(institution));
        when(adminCatalogFacade.listAffiliationsByNameContains("vest")).thenReturn(List.of(affiliation));

        mockMvc.perform(get("/admin/institutions"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/institutions"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"editInstitutionModal\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("data-app-modal-shell")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("aria-labelledby=\"editInstitutionModalLabel\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("data-edit-institution-id=\"UVT\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"edit-institution-form\"")));
    }

    @Test
    void institutionEditPageRedirectsAndEditDataReturnsJson() throws Exception {
        Institution institution = new Institution();
        institution.setName("UVT");
        institution.setDescription("University");
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setAfid("af1");
        affiliation.setName("Aff One");
        institution.setScopusAffiliations(List.of(affiliation));
        institution.setWosAffiliations(List.of("West University"));

        when(adminCatalogFacade.findInstitutionById("UVT")).thenReturn(Optional.of(institution));

        mockMvc.perform(get("/admin/institutions/edit/{id}", "UVT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/institutions"));

        mockMvc.perform(get("/admin/institutions/{id}/edit-data", "UVT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("UVT"))
                .andExpect(jsonPath("$.description").value("University"))
                .andExpect(jsonPath("$.scopusAffiliations[0].id").value("af1"))
                .andExpect(jsonPath("$.scopusAffiliations[0].name").value("Aff One"))
                .andExpect(jsonPath("$.wosAffiliations[0]").value("West University"));
    }

    @Test
    void oldIndicatorDeleteGetRouteIsNoLongerMapped() throws Exception {
        mockMvc.perform(get("/admin/indicators/delete/{id}", "ind-1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void institutionPublicationsRedirectsToWorkspace() throws Exception {
        mockMvc.perform(get("/admin/institutions/{id}/publications", "i1")
                        .with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/institutions/i1#publications"));
    }

    @Test
    void institutionExportExcelReturnsWorkbookHeaders() throws Exception {
        ScholardexPublicationView publication = publication("p1", "f1", "2023-01-01");
        publication.setEid("2-s2.0-123");
        publication.setDoi("10.1000/x");
        publication.setTitle("Paper");
        publication.setCitedbyCount(5);
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Author A");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum A");

        ScholardexPublicationView citing = publication("p2", "f1", "2024-01-01");
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
                        "selectors",
                        "adminFormObject",
                        "breadcrumbs"
                ))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"indicator-admin-form\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("app-admin-form__header")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("aria-label=\"Breadcrumb\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("href=\"/admin/indicators\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"scoringYearRange\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"yearRange\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("id=\"criterionFormula\"")));
    }

    private static ScholardexPublicationView publication(String id, String forumId, String coverDate) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
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
