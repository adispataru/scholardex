package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.GroupCnfisExportFacade;
import ro.uvt.pokedex.core.service.application.GroupExportFacade;
import ro.uvt.pokedex.core.service.application.GroupManagementFacade;
import ro.uvt.pokedex.core.service.application.GroupReportFacade;
import ro.uvt.pokedex.core.service.application.model.GroupCnfisZipExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupEditViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupIndividualReportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupMemberCnfisWorkbook;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationCsvExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupWorkbookExportResult;
import ro.uvt.pokedex.core.service.importing.GroupService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminGroupController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminGroupControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

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
    void editGroupRendersSharedAdminFormBaseline() throws Exception {
        Group group = new Group();
        group.setId("g1");
        group.setName("Group One");
        group.setDescription("Research group");
        group.setDomains(List.of());
        group.setMemberIds(List.of());

        Domain domain = new Domain();
        domain.setName("CS");
        Institution institution = new Institution();
        institution.setName("UVT");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(domain), List.of(institution), List.of(new User())));

        mockMvc.perform(get("/admin/groups/edit/{id}", "g1"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/edit-group"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"group-admin-form\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app-admin-form__header")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"Breadcrumb\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin/groups\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"domainsContainer\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"researchersContainer\"")));
    }

    @Test
    void groupPublicationsViewRendersVenueQualityChartData() throws Exception {
        Group group = new Group();
        group.setId("g1");
        group.setName("Group One");

        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        publication.setTitle("Paper One");
        publication.setAuthors(List.of("a1"));
        publication.setForum("f1");
        publication.setCoverDate("2023-01-01");

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Author One");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setTitle("Report One");

        when(groupReportFacade.buildGroupPublicationsView("g1"))
                .thenReturn(Optional.of(new GroupPublicationsViewModel(
                        group,
                        List.of(),
                        List.of(publication),
                        Map.of("a1", author),
                        Map.of("f1", forum),
                        Map.of(2023, List.of(publication)),
                        Map.of(2023, 1L),
                        Map.of(2023, Map.ofEntries(
                                Map.entry("Q1", 1L),
                                Map.entry("Q2", 0L),
                                Map.entry("Q3", 0L),
                                Map.entry("Q4", 0L),
                                Map.entry("A_STAR", 0L),
                                Map.entry("A", 0L),
                                Map.entry("B", 0L),
                                Map.entry("C", 0L),
                                Map.entry("D", 0L),
                                Map.entry("LNCS", 0L),
                                Map.entry("BOOK_LNCS", 0L),
                                Map.entry("SCOPUS", 0L),
                                Map.entry("NON_RANK", 0L),
                                Map.entry("Unranked", 0L)
                        )),
                        List.of(report)
                )));

        mockMvc.perform(get("/admin/groups/{id}/publications", "g1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/groups/g1#publications"));
    }

    @Test
    void exportCnfis2025ReturnsWorkbookHeadersAndBody() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        when(groupCnfisExportFacade.buildGroupCnfisWorkbookExport(eq("g1"), eq(2021), eq(2024)))
                .thenReturn(Optional.of(new GroupWorkbookExportResult(
                        bytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx"
                )));

        mockMvc.perform(get("/admin/groups/{id}/publications/exportCNFIS2025", "g1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx\""))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void exportCnfis2025MissingGroupReturnsEmptyResponseWithoutHeaders() throws Exception {
        when(groupCnfisExportFacade.buildGroupCnfisWorkbookExport(eq("missing"), eq(2021), eq(2024)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/groups/{id}/publications/exportCNFIS2025", "missing"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void exportCnfis2025WithInvalidStartYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/admin/groups/{id}/publications/exportCNFIS2025", "g1")
                        .param("start", "bad")
                        .param("end", "2024"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportCnfis2025WithInvalidEndYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/admin/groups/{id}/publications/exportCNFIS2025", "g1")
                        .param("start", "2021")
                        .param("end", "bad"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportCnfis2025WithStartGreaterThanEndReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/admin/groups/{id}/publications/exportCNFIS2025", "g1")
                        .param("start", "2024")
                        .param("end", "2021"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportCnfis2025WithOutOfRangeYearReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/admin/groups/{id}/publications/exportCNFIS2025", "g1")
                        .param("start", "1899")
                        .param("end", "2024"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportAllReportsReturnsZipResponseContract() throws Exception {
        when(groupCnfisExportFacade.buildGroupCnfisZipExport(eq("g1"), eq(2021), eq(2024)))
                .thenReturn(Optional.of(new GroupCnfisZipExportViewModel(
                        List.of(new GroupMemberCnfisWorkbook("Popescu_A_AB.xlsx", new byte[]{7, 8, 9}))
                )));

        byte[] responseBytes = mockMvc.perform(get("/admin/groups/{id}/publications/exportAllReports", "g1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=group_reports.zip"))
                .andExpect(content().contentType("application/zip"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertTrue(responseBytes.length > 4);
        assertTrue(responseBytes[0] == 'P' && responseBytes[1] == 'K');
    }

    @Test
    void exportIndicatorResultsReturnsExpectedCsvHeaderAndRowContract() throws Exception {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setDoi("10.1000/test");
        publication.setTitle("CSV Paper");
        publication.setAuthors(List.of("a1"));
        publication.setForum("f1");
        publication.setCoverDate("2023-02-15");
        publication.setVolume("12");
        publication.setIssueIdentifier("3");
        publication.setPageRange("10-20");

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Author One");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        when(groupExportFacade.buildGroupPublicationCsvExport(eq("g1")))
                .thenReturn(Optional.of(new GroupPublicationCsvExportViewModel(
                        List.of(publication),
                        Map.of("a1", author),
                        Map.of("f1", forum),
                        Set.of("a1")
                )));

        String csv = mockMvc.perform(get("/admin/groups/{id}/publications/export", "g1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"group_publications.csv\""))
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String[] lines = csv.split("\\R");
        assertTrue(lines[0].startsWith("DOI,Title,Authors,Affiliated Authors,Forum,Year,Volume,Page Range"));
        assertTrue(lines[1].contains("10.1000/test"));
        assertTrue(lines[1].contains("\"CSV Paper\""));
        assertTrue(lines[1].contains("\"Author One\""));
        assertTrue(lines[1].contains("\"Forum One\""));
        assertTrue(lines[1].contains("2023"));
        assertTrue(lines[1].contains("12(3)"));
        assertTrue(lines[1].contains("10-20"));
    }

    @Test
    void importGroupsWithEmptyFileRedirectsWithErrorFlash() throws Exception {
        mockMvc.perform(multipart("/admin/groups/import")
                        .file("file", new byte[0]))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/groups"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void deleteGroupPostRouteRedirectsAndDelegates() throws Exception {
        mockMvc.perform(post("/admin/groups/delete/{id}", "g1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/groups"));

        verify(groupManagementFacade).deleteGroup("g1");
    }

    @Test
    void importGroupsWithWrongExtensionRedirectsWithErrorFlash() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "groups.txt",
                "text/csv",
                "x".getBytes()
        );
        mockMvc.perform(multipart("/admin/groups/import")
                        .file(file))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/groups"))
                .andExpect(flash().attributeExists("errorMessage"));

        verify(groupService, never()).importGroupsFromCsv(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void importGroupsWithUnsupportedContentTypeRedirectsWithErrorFlash() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "groups.csv",
                "application/json",
                "x".getBytes()
        );
        mockMvc.perform(multipart("/admin/groups/import").file(file))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/groups"))
                .andExpect(flash().attributeExists("errorMessage"));

        verify(groupService, never()).importGroupsFromCsv(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteGroupGetRouteIsNoLongerMapped() throws Exception {
        mockMvc.perform(get("/admin/groups/delete/{id}", "g1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void refreshIndividualReportRedirectsToViewRoute() throws Exception {
        when(groupReportFacade.refreshGroupIndividualReportView("g1", "rep1"))
                .thenReturn(new GroupIndividualReportViewModel(null, Map.of()));

        mockMvc.perform(post("/admin/groups/{gid}/reports/view/{id}/refresh", "g1", "rep1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/groups/g1/reports/view/rep1"));
    }

    @Test
    void groupIndividualReportViewDisplaysCriterionNameOrFallback() throws Exception {
        Group group = new Group();
        group.setId("g1");
        group.setName("G");

        User member = new User();
        member.setEmail("r1");
        User.ResearcherProfile rProfile = new User.ResearcherProfile();
        rProfile.setFirstName("A");
        rProfile.setLastName("B");
        rProfile.setPosition(Position.OTHER);
        member.setResearcherProfile(rProfile);

        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setTitle("Group Report");
        report.setDescription("Desc");
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion named = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        named.setName("Citations");
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion unnamed = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        unnamed.setName(" ");
        report.setCriteria(List.of(named, unnamed));

        when(groupReportFacade.buildGroupIndividualReportView("g1", "rep1"))
                .thenReturn(new GroupIndividualReportViewModel(
                        null,
                        Map.of(
                                "report", report,
                                "group", group,
                                "researchers", List.of(member),
                                "researcherScores", Map.of("r1", Map.of(0, 1.0, 1, 2.0)),
                                "criteriaThresholds", Map.of(),
                                "runCreatedAt", Instant.parse("2026-03-05T10:00:00Z"),
                                "runStatus", "READY",
                                "runBuildErrors", List.of()
                        )
                ));

        String html = mockMvc.perform(get("/admin/groups/{gid}/reports/view/{id}", "g1", "rep1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(html.contains("Citations"));
        assertTrue(html.contains("Criterion 2"));
    }

    @Test
    void groupIndividualReportViewDoesNotFailWhenThresholdForPositionIsMissing() throws Exception {
        Group group = new Group();
        group.setId("g1");
        group.setName("G");

        User member = new User();
        member.setEmail("r1");
        User.ResearcherProfile rProfile2 = new User.ResearcherProfile();
        rProfile2.setFirstName("A");
        rProfile2.setLastName("B");
        rProfile2.setPosition(Position.ASIST_UNIV);
        member.setResearcherProfile(rProfile2);

        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setTitle("Group Report");
        report.setDescription("Desc");
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion criterion = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        criterion.setName("Citations");
        report.setCriteria(List.of(criterion));

        when(groupReportFacade.buildGroupIndividualReportView("g1", "rep1"))
                .thenReturn(new GroupIndividualReportViewModel(
                        null,
                        Map.of(
                                "report", report,
                                "group", group,
                                "researchers", List.of(member),
                                "researcherScores", Map.of("r1", Map.of(0, 1.0)),
                                "criteriaThresholds", Map.of(0, Map.of()),
                                "runCreatedAt", Instant.parse("2026-03-05T10:00:00Z"),
                                "runStatus", "READY",
                                "runBuildErrors", List.of()
                        )
                ));

        mockMvc.perform(get("/admin/groups/{gid}/reports/view/{id}", "g1", "rep1"))
                .andExpect(status().isOk());
    }
}
