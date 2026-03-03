package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.scopus.Author;
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
        researcher.getScopusId().add("a1");

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
}
