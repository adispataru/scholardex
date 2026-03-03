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
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminScopusFacadeTest {

    @Mock
    private ScopusPublicationRepository scopusPublicationRepository;
    @Mock
    private ScopusAuthorRepository scopusAuthorRepository;
    @Mock
    private ScopusCitationRepository scopusCitationRepository;
    @Mock
    private ScopusForumRepository scopusForumRepository;

    @InjectMocks
    private AdminScopusFacade facade;

    @Test
    void buildPublicationSearchViewReturnsPublicationsAndAuthorMap() {
        Publication publication = publication("p1", "f1", List.of("a1"));
        Author author = author("a1", "Author One");
        when(scopusPublicationRepository.findByTitleContains("paper")).thenReturn(List.of(publication));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author));

        var vm = facade.buildPublicationSearchView("paper");

        assertEquals(1, vm.publications().size());
        assertEquals(1, vm.authorMap().size());
        assertEquals("Author One", vm.authorMap().get("a1").getName());
    }

    @Test
    void buildPublicationCitationsViewReturnsEmptyWhenPublicationMissing() {
        when(scopusPublicationRepository.findById("missing")).thenReturn(Optional.empty());

        var vm = facade.buildPublicationCitationsView("missing");

        assertTrue(vm.isEmpty());
    }

    @Test
    void buildPublicationCitationsViewBuildsCitationsAndLookupMaps() {
        Publication publication = publication("p1", "f1", List.of("a1"));
        Publication citing = publication("p2", "f2", List.of("a2"));
        Citation citation = new Citation();
        citation.setCitedId("p1");
        citation.setCitingId("p2");

        Forum forum1 = forum("f1", "Forum One");
        Forum forum2 = forum("f2", "Forum Two");

        when(scopusPublicationRepository.findById("p1")).thenReturn(Optional.of(publication));
        when(scopusCitationRepository.findAllByCitedId("p1")).thenReturn(List.of(citation));
        when(scopusPublicationRepository.findAllByIdIn(List.of("p2"))).thenReturn(List.of(citing));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author("a1", "A1"), author("a2", "A2")));
        when(scopusForumRepository.findByIdIn(anyCollection())).thenReturn(List.of(forum2));
        when(scopusForumRepository.findById("f1")).thenReturn(Optional.of(forum1));

        var vm = facade.buildPublicationCitationsView("p1");

        assertTrue(vm.isPresent());
        assertEquals(1, vm.get().citations().size());
        assertEquals("Forum One", vm.get().publicationForum().getPublicationName());
        assertEquals(2, vm.get().authorMap().size());
        assertEquals(1, vm.get().forumMap().size());
    }

    private static Publication publication(String id, String forumId, List<String> authors) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setForum(forumId);
        publication.setAuthors(authors);
        publication.setTitle(id);
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
