package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAdminReadPort;
import ro.uvt.pokedex.core.service.application.model.ScholardexCitationsView;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationSearchView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private PostgresScholardexAdminReadPort postgresScholardexAdminReadPort;

    @Test
    void searchRouteBuildsPublicationSearchView() throws Exception {
        Publication publication = new Publication();
        publication.setId("p1");
        Author author = new Author();
        author.setId("a1");
        when(postgresScholardexAdminReadPort.buildPublicationSearchView("Paper"))
                .thenReturn(new ScholardexPublicationSearchView(List.of(publication), Map.of("a1", author)));

        mockMvc.perform(get("/admin/scholardex/publications/search")
                        .param("authorName", "Author")
                        .param("paperTitle", "Paper"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scholardex-publications-search"))
                .andExpect(model().attributeExists("authorMap"))
                .andExpect(model().attributeExists("publications"));

        verify(postgresScholardexAdminReadPort).buildPublicationSearchView("Paper");
    }

    @Test
    void citationsRouteBuildsPublicationCitationsView() throws Exception {
        Publication publication = new Publication();
        publication.setId("p1");
        publication.setTitle("Publication One");
        publication.setAuthors(List.of("a1"));
        publication.setSubtypeDescription("Journal Article");
        publication.setVolume("10");
        publication.setIssueIdentifier("2");
        Publication citation = new Publication();
        citation.setId("c1");
        citation.setTitle("Citation One");
        citation.setCoverDate("2024");
        citation.setForum("f1");
        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum One");
        forum.setIssn("1234-5678");
        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");
        when(postgresScholardexAdminReadPort.buildPublicationCitationsView("p1"))
                .thenReturn(Optional.of(new ScholardexCitationsView(
                        publication,
                        forum,
                        List.of(citation),
                        Map.of("a1", author),
                        Map.of("f1", forum)
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

        verify(postgresScholardexAdminReadPort).buildPublicationCitationsView("p1");
    }
}
