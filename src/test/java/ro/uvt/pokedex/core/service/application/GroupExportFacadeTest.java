package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.model.GroupEditViewModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupExportFacadeTest {

    @Mock
    private GroupManagementFacade groupManagementFacade;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;

    @InjectMocks
    private GroupExportFacade facade;

    @BeforeEach
    void setUpLookupService() {
        lenient().when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class)))
                .thenAnswer(invocation -> {
                    Researcher researcher = invocation.getArgument(0);
                    return researcher.getScopusId() == null ? List.of() : researcher.getScopusId();
                });
    }

    @Test
    void buildGroupPublicationCsvExportReturnsEmptyWhenGroupMissing() {
        when(groupManagementFacade.buildGroupEditView("missing"))
                .thenReturn(new GroupEditViewModel(null, List.of(), List.of(), List.of()));

        Optional<?> result = facade.buildGroupPublicationCsvExport("missing");

        assertTrue(result.isEmpty());
    }

    @Test
    void buildGroupPublicationCsvExportBuildsPublicationAndLookupMaps() {
        Researcher researcher = new Researcher();
        researcher.setFirstName("Jane");
        researcher.setLastName("Doe");
        researcher.setScopusId(List.of("a1"));

        Group group = new Group();
        group.setResearchers(List.of(researcher));

        Publication publication = new Publication();
        publication.setAuthors(List.of("a1"));
        publication.setForum("f1");

        Author author = new Author();
        author.setId("a1");
        author.setName("Jane Doe");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of()));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection()))
                .thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection()))
                .thenReturn(List.of(forum));

        var result = facade.buildGroupPublicationCsvExport("g1");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().publications().size());
        assertEquals("Jane Doe", result.get().authorMap().get("a1").getName());
        assertEquals("Forum One", result.get().forumMap().get("f1").getPublicationName());
        assertTrue(result.get().affiliatedAuthorIds().contains("a1"));
    }

    @Test
    void buildGroupPublicationCsvExportSortsAndDedupesPublications() {
        Researcher researcher = new Researcher();
        researcher.setFirstName("Jane");
        researcher.setLastName("Doe");
        researcher.setScopusId(List.of("a1"));

        Group group = new Group();
        group.setResearchers(List.of(researcher));

        Publication p1 = new Publication();
        p1.setId("p1");
        p1.setTitle("Beta");
        p1.setCoverDate("2024-01-01");
        p1.setAuthors(List.of("a1"));
        p1.setForum("f1");

        Publication p2 = new Publication();
        p2.setId("p2");
        p2.setTitle("Alpha");
        p2.setCoverDate("2024-01-01");
        p2.setAuthors(List.of("a1"));
        p2.setForum("f1");

        Publication malformed = new Publication();
        malformed.setId("p3");
        malformed.setTitle("Zeta");
        malformed.setCoverDate("bad-date");
        malformed.setAuthors(List.of("a1"));
        malformed.setForum("f1");

        Author author = new Author();
        author.setId("a1");
        author.setName("Jane Doe");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        when(groupManagementFacade.buildGroupEditView("g1"))
                .thenReturn(new GroupEditViewModel(group, List.of(), List.of(), List.of()));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1")))
                .thenReturn(List.of(p1, malformed, p2, p1));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection()))
                .thenReturn(List.of(author));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection()))
                .thenReturn(List.of(forum));

        var result = facade.buildGroupPublicationCsvExport("g1");

        assertTrue(result.isPresent());
        assertEquals(List.of("p2", "p1", "p3"),
                result.get().publications().stream().map(Publication::getId).toList());
    }
}
