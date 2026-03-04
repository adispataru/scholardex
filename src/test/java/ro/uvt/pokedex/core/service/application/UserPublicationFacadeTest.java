package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.ResearcherService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPublicationFacadeTest {

    @Mock
    private ResearcherService researcherService;
    @Mock
    private ScopusAuthorRepository scopusAuthorRepository;
    @Mock
    private ScopusCitationRepository scopusCitationRepository;
    @Mock
    private ScopusPublicationRepository scopusPublicationRepository;
    @Mock
    private ScopusForumRepository scopusForumRepository;

    @InjectMocks
    private UserPublicationFacade facade;

    @Test
    void buildUserPublicationsViewBuildsMapsAndHIndex() {
        Researcher researcher = new Researcher();
        researcher.setScopusId(List.of("a1"));

        Author author = new Author();
        author.setId("a1");
        author.setName("Alice");

        Publication p = new Publication();
        p.setId("p1");
        p.setTitle("T1");
        p.setForum("f1");
        p.setAuthors(new java.util.ArrayList<>());
        p.getAuthors().add("a1");
        p.setCitedbyCount(3);

        Forum forum = new Forum();
        forum.setId("f1");

        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scopusPublicationRepository.findAllByAuthorsContaining("a1")).thenReturn(List.of(p));
        when(scopusForumRepository.findByIdIn(anyCollection())).thenReturn(List.of(forum));

        var vmOpt = facade.buildUserPublicationsView("r1");

        assertTrue(vmOpt.isPresent());
        var vm = vmOpt.get();
        assertEquals(1, vm.publications().size());
        assertEquals(1, vm.hIndex());
        assertEquals(3, vm.numCitations());
        assertEquals("a1", vm.authorMap().get("a1").getId());
        assertEquals("f1", vm.forumMap().get("f1").getId());
    }

    @Test
    void buildUserPublicationsViewDedupesByPublicationIdAcrossMultipleAuthors() {
        Researcher researcher = new Researcher();
        researcher.setScopusId(List.of("a1", "a2"));

        Author author1 = new Author();
        author1.setId("a1");
        Author author2 = new Author();
        author2.setId("a2");

        Publication shared = new Publication();
        shared.setId("p-shared");
        shared.setTitle("Shared");
        shared.setForum("f1");
        shared.setAuthors(List.of("a1", "a2"));
        shared.setCitedbyCount(2);
        shared.setCoverDate("2022-01-01");

        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author1, author2));
        when(scopusPublicationRepository.findAllByAuthorsContaining("a1")).thenReturn(List.of(shared));
        when(scopusPublicationRepository.findAllByAuthorsContaining("a2")).thenReturn(List.of(shared));
        when(scopusForumRepository.findByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));

        var vmOpt = facade.buildUserPublicationsView("r1");

        assertTrue(vmOpt.isPresent());
        assertEquals(1, vmOpt.get().publications().size());
        assertEquals(2, vmOpt.get().numCitations());
    }

    @Test
    void buildUserPublicationsViewUsesDeterministicOrderingContract() {
        Researcher researcher = new Researcher();
        researcher.setScopusId(List.of("a1"));

        Author author = new Author();
        author.setId("a1");

        Publication malformed = publication("p3", "Zeta", "bad-date", 1, "f1", List.of("a1"));
        Publication newest = publication("p2", "Alpha", "2024-02-01", 1, "f1", List.of("a1"));
        Publication sameYearHigherTitle = publication("p1", "Beta", "2024-01-10", 1, "f1", List.of("a1"));

        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author));
        when(scopusPublicationRepository.findAllByAuthorsContaining("a1"))
                .thenReturn(List.of(malformed, sameYearHigherTitle, newest));
        when(scopusForumRepository.findByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));

        var vmOpt = facade.buildUserPublicationsView("r1");

        assertTrue(vmOpt.isPresent());
        assertEquals(List.of("p2", "p1", "p3"), vmOpt.get().publications().stream().map(Publication::getId).toList());
    }

    @Test
    void buildCitationsViewSortsCitationsDeterministically() {
        Publication publication = publication("p1", "Main", "2023-01-01", 0, "f1", List.of("a1"));
        Publication c1 = publication("c1", "Zulu", "bad-date", 0, "f2", List.of("a2"));
        Publication c2 = publication("c2", "Alpha", "2024-01-01", 0, "f2", List.of("a3"));
        Publication c3 = publication("c3", "Beta", "2024-01-01", 0, "f2", List.of("a4"));
        Citation link1 = citation("p1", "c1");
        Citation link2 = citation("p1", "c2");
        Citation link3 = citation("p1", "c3");

        when(scopusPublicationRepository.findById("p1")).thenReturn(Optional.of(publication));
        when(scopusCitationRepository.findAllByCitedId("p1")).thenReturn(List.of(link1, link2, link3));
        when(scopusPublicationRepository.findAllByIdIn(List.of("c1", "c2", "c3"))).thenReturn(List.of(c1, c3, c2));
        when(scopusForumRepository.findById("f1")).thenReturn(Optional.of(forum("f1")));
        when(scopusAuthorRepository.findByIdIn(anyCollection())).thenReturn(List.of(author("a1")));
        when(scopusForumRepository.findByIdIn(anyCollection())).thenReturn(List.of(forum("f2")));

        var vmOpt = facade.buildCitationsView("p1");

        assertTrue(vmOpt.isPresent());
        assertEquals(List.of("c2", "c3", "c1"), vmOpt.get().citations().stream().map(Publication::getId).toList());
    }

    @Test
    void findPublicationForEditUsesCanonicalIdLookup() {
        Publication publication = publication("p1", "P", "2023-01-01", 0, "f1", List.of("a1"));
        when(scopusPublicationRepository.findById("p1")).thenReturn(Optional.of(publication));

        var result = facade.findPublicationForEdit("p1");

        assertTrue(result.isPresent());
        verify(scopusPublicationRepository).findById("p1");
    }

    @Test
    void updatePublicationMetadataUsesCanonicalIdLookupAndSave() {
        Publication existing = publication("p1", "Old", "2023-01-01", 0, "f1", List.of("a1"));
        Publication patch = new Publication();
        patch.setSubtype("cp");
        patch.setSubtypeDescription("Proceedings");
        when(scopusPublicationRepository.findById("p1")).thenReturn(Optional.of(existing));

        facade.updatePublicationMetadata("p1", patch);

        assertEquals("cp", existing.getSubtype());
        assertEquals("Proceedings", existing.getSubtypeDescription());
        verify(scopusPublicationRepository).save(existing);
    }

    private static Publication publication(String id, String title, String coverDate, int citedByCount, String forumId, List<String> authors) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setTitle(title);
        publication.setCoverDate(coverDate);
        publication.setCitedbyCount(citedByCount);
        publication.setForum(forumId);
        publication.setAuthors(authors);
        return publication;
    }

    private static Citation citation(String citedId, String citingId) {
        Citation citation = new Citation();
        citation.setCitedId(citedId);
        citation.setCitingId(citingId);
        return citation;
    }

    private static Forum forum(String id) {
        Forum forum = new Forum();
        forum.setId(id);
        forum.setPublicationName(id);
        return forum;
    }

    private static Author author(String id) {
        Author author = new Author();
        author.setId(id);
        author.setName(id);
        return author;
    }
}
