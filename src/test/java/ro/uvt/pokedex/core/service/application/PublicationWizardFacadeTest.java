package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.scopus.ScopusAffiliationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicationWizardFacadeTest {

    @Mock
    private ScopusForumRepository forumRepository;
    @Mock
    private ScopusAuthorRepository authorRepository;
    @Mock
    private ScopusPublicationRepository publicationRepository;
    @Mock
    private ScopusAffiliationRepository affiliationRepository;

    @InjectMocks
    private PublicationWizardFacade facade;

    @Test
    void resolveForumIdUsesSelectedExistingForum() {
        Forum existing = new Forum();
        existing.setId("f1");
        when(forumRepository.findById("f1")).thenReturn(Optional.of(existing));

        assertEquals("f1", facade.resolveForumId(new Forum(), "f1"));
    }

    @Test
    void findAuthorsForAffiliationReturnsMatches() {
        Affiliation affiliation = new Affiliation();
        Author author = new Author();
        when(affiliationRepository.findById("af1")).thenReturn(Optional.of(affiliation));
        when(authorRepository.findAllByAffiliationsContaining(affiliation)).thenReturn(List.of(author));

        assertEquals(1, facade.findAuthorsForAffiliation("af1").size());
    }

    @Test
    void buildAndSavePublicationDelegates() {
        Publication publication = facade.buildPublicationDraft("f1", "a1,a2", "r1");
        assertEquals("f1", publication.getForum());
        assertEquals(2, publication.getAuthors().size());

        facade.savePublication(publication);
        verify(publicationRepository).save(publication);
    }
}
