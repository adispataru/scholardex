package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminScopusFacadeTest {

    @Mock
    private ScopusProjectionReadService scopusProjectionReadService;

    @InjectMocks
    private MongoAdminScopusReadPort mongoAdminScopusReadPort;

    @Test
    void buildPublicationSearchViewReturnsPublicationsAndAuthorMap() {
        Publication publication = publication("p1", "f1", List.of("a1"), "2024-01-01", "Paper");
        Author author = author("a1", "Author One");
        when(scopusProjectionReadService.findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc("paper")).thenReturn(List.of(publication));
        when(scopusProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));

        var vm = mongoAdminScopusReadPort.buildPublicationSearchView("paper");

        assertEquals(1, vm.publications().size());
        assertEquals(1, vm.authorMap().size());
        assertEquals("Author One", vm.authorMap().get("a1").getName());
    }

    @Test
    void buildPublicationSearchViewAppliesDeterministicOrderingTieBreaks() {
        Publication p1 = publication("p1", "f1", List.of("a1"), "2024-01-01", "Beta");
        Publication p2 = publication("p2", "f1", List.of("a1"), "2024-01-01", "Alpha");
        Publication p3 = publication("p3", "f1", List.of("a1"), "bad-date", "Zeta");
        when(scopusProjectionReadService.findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc("paper")).thenReturn(List.of(p1, p3, p2));
        when(scopusProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1", "Author One")));

        var vm = mongoAdminScopusReadPort.buildPublicationSearchView("paper");

        assertEquals(List.of("p2", "p1", "p3"), vm.publications().stream().map(Publication::getId).toList());
    }

    @Test
    void buildPublicationCitationsViewReturnsEmptyWhenPublicationMissing() {
        when(scopusProjectionReadService.findPublicationByAnyId("missing")).thenReturn(Optional.empty());

        var vm = mongoAdminScopusReadPort.buildPublicationCitationsView("missing");

        assertTrue(vm.isEmpty());
    }

    @Test
    void buildPublicationCitationsViewBuildsCitationsAndLookupMaps() {
        Publication publication = publication("p1", "f1", List.of("a1"), "2023-01-01", "Main");
        Publication citing = publication("p2", "f2", List.of("a2"), "2024-01-01", "Citing");
        Citation citation = new Citation();
        citation.setCitedId("p1");
        citation.setCitingId("p2");

        Forum forum1 = forum("f1", "Forum One");
        Forum forum2 = forum("f2", "Forum Two");

        when(scopusProjectionReadService.findPublicationByAnyId("p1")).thenReturn(Optional.of(publication));
        when(scopusProjectionReadService.findAllCitationsByCitedId("p1")).thenReturn(List.of(citation));
        when(scopusProjectionReadService.findAllPublicationsByIdIn(List.of("p2"))).thenReturn(List.of(citing));
        when(scopusProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1", "A1"), author("a2", "A2")));
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum2));
        when(scopusProjectionReadService.findForumById("f1")).thenReturn(Optional.of(forum1));

        var vm = mongoAdminScopusReadPort.buildPublicationCitationsView("p1");

        assertTrue(vm.isPresent());
        assertEquals(1, vm.get().citations().size());
        assertEquals("Forum One", vm.get().publicationForum().getPublicationName());
        assertEquals(2, vm.get().authorMap().size());
        assertEquals(1, vm.get().forumMap().size());
    }

    @Test
    void buildPublicationCitationsViewSortsCitationsDeterministically() {
        Publication publication = publication("p1", "f1", List.of("a1"), "2023-01-01", "Main");
        Publication c1 = publication("c1", "f2", List.of("a2"), "bad-date", "Zeta");
        Publication c2 = publication("c2", "f2", List.of("a3"), "2024-01-01", "Alpha");
        Publication c3 = publication("c3", "f2", List.of("a4"), "2024-01-01", "Beta");
        Citation cit1 = new Citation();
        cit1.setCitedId("p1");
        cit1.setCitingId("c1");
        Citation cit2 = new Citation();
        cit2.setCitedId("p1");
        cit2.setCitingId("c2");
        Citation cit3 = new Citation();
        cit3.setCitedId("p1");
        cit3.setCitingId("c3");

        when(scopusProjectionReadService.findPublicationByAnyId("p1")).thenReturn(Optional.of(publication));
        when(scopusProjectionReadService.findAllCitationsByCitedId("p1")).thenReturn(List.of(cit1, cit2, cit3));
        when(scopusProjectionReadService.findAllPublicationsByIdIn(List.of("c1", "c2", "c3"))).thenReturn(List.of(c1, c3, c2));
        when(scopusProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(
                author("a1", "A1"), author("a2", "A2"), author("a3", "A3"), author("a4", "A4")));
        when(scopusProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f2", "Forum Two")));
        when(scopusProjectionReadService.findForumById("f1")).thenReturn(Optional.of(forum("f1", "Forum One")));

        var vm = mongoAdminScopusReadPort.buildPublicationCitationsView("p1");

        assertTrue(vm.isPresent());
        assertEquals(List.of("c2", "c3", "c1"), vm.get().citations().stream().map(Publication::getId).toList());
    }

    private static Publication publication(String id, String forumId, List<String> authors, String coverDate, String title) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setForum(forumId);
        publication.setAuthors(authors);
        publication.setCoverDate(coverDate);
        publication.setTitle(title);
        return publication;
    }

    private static Author author(String id, String name) {
        Author author = new Author();
        author.setId(id);
        author.setName(name);
        return author;
    }

    private static Forum forum(String id, String publicationName) {
        Forum forum = new Forum();
        forum.setId(id);
        forum.setPublicationName(publicationName);
        return forum;
    }
}
