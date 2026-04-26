package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.AdminDashboardService;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAdminReadPort;
import ro.uvt.pokedex.core.service.application.model.AdminOperationStatus;
import ro.uvt.pokedex.core.service.application.model.PublicationAuthorshipDecisionAdminSummary;
import ro.uvt.pokedex.core.service.application.model.ScholardexCitationsView;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationSearchView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(
        value = AdminScholardexPublicationViewController.class,
        properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/test"
)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminScholardexPublicationViewControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminDashboardService adminDashboardService;
    @MockitoBean
    private PostgresScholardexAdminReadPort postgresScholardexAdminReadPort;

    @Test
    void searchRouteRedirectsToPublicationCatalogWithQueryParams() throws Exception {
        mockMvc.perform(get("/admin/scholardex/publications/search")
                        .param("paperTitle", "Paper"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern("/admin/scholardex/publications*"));
    }

    @Test
    void citationsRouteBuildsPublicationCitationsView() throws Exception {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        publication.setTitle("Publication One");
        publication.setAuthors(List.of("a1"));
        publication.setSubtypeDescription("Journal Article");
        publication.setVolume("10");
        publication.setIssueIdentifier("2");
        ScholardexPublicationView citation = new ScholardexPublicationView();
        citation.setId("c1");
        citation.setTitle("Citation One");
        citation.setCoverDate("2024");
        citation.setForum("f1");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum One");
        forum.setIssn("1234-5678");
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Author One");
        when(adminDashboardService.buildCitationSyncStatus())
                .thenReturn(AdminOperationStatus.neverRun());
        when(postgresScholardexAdminReadPort.buildPublicationCitationsView("p1", 0, 25))
                .thenReturn(Optional.of(new ScholardexCitationsView(
                        publication,
                        forum,
                        List.of(citation),
                        Map.of("a1", author),
                        Map.of("f1", forum),
                        1L, 0, 25, 1
                )));

        mockMvc.perform(get("/admin/scholardex/publications/citations")
                        .param("id", "p1"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scholardex-citations"))
                .andExpect(model().attributeExists("citations"))
                .andExpect(model().attributeExists("publication"))
                .andExpect(model().attributeExists("forum"))
                .andExpect(model().attributeExists("authorMap"))
                .andExpect(model().attributeExists("forumMap"));

        verify(postgresScholardexAdminReadPort).buildPublicationCitationsView("p1", 0, 25);
    }

    @Test
    void scholardexPublicationsPagesRenderCanonicalTemplates() throws Exception {
        when(adminDashboardService.buildPublicationCatalogStats())
                .thenReturn(new AdminDashboardService.PublicationCatalogStats(0L, 0L));
        when(postgresScholardexAdminReadPort.buildPublicationCatalogPage(
                any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(new PostgresScholardexAdminReadPort.PublicationCatalogPage(
                        List.of(), Map.of(), Map.of(), Map.of(), 0L, 0, 25, 0));

        mockMvc.perform(get("/admin/scholardex/publications"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scholardex-publications"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("/admin/scholardex/publications/search")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/admin/scopus/publications"))));
    }
}
