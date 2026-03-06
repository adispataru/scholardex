package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusProjectionBuilderServiceTest {

    @Mock
    private ScopusForumFactRepository forumFactRepository;
    @Mock
    private ScopusAuthorFactRepository authorFactRepository;
    @Mock
    private ScopusAffiliationFactRepository affiliationFactRepository;
    @Mock
    private ScopusPublicationFactRepository publicationFactRepository;
    @Mock
    private ScopusCitationFactRepository citationFactRepository;
    @Mock
    private ScopusForumSearchViewRepository forumSearchViewRepository;
    @Mock
    private ScopusAuthorSearchViewRepository authorSearchViewRepository;
    @Mock
    private ScopusAffiliationSearchViewRepository affiliationSearchViewRepository;
    @Mock
    private ScholardexPublicationViewRepository publicationViewRepository;

    @Test
    void rebuildViewsPreservesExistingWosEnrichment() {
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
                forumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                forumSearchViewRepository,
                authorSearchViewRepository,
                affiliationSearchViewRepository,
                publicationViewRepository
        );

        ScopusPublicationFact publicationFact = new ScopusPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-1");
        publicationFact.setTitle("Paper");
        publicationFact.setAuthors(List.of("a1"));
        publicationFact.setAffiliations(List.of("af1"));
        publicationFact.setCitedByCount(1);
        publicationFact.setSourceEventId("ev1");

        ScholardexPublicationView existing = new ScholardexPublicationView();
        existing.setId("p1");
        existing.setEid("2-s2.0-1");
        existing.setWosId("WOS:0001");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of(publicationFact));
        when(citationFactRepository.findAll()).thenReturn(List.of());
        when(publicationViewRepository.findAll()).thenReturn(List.of(existing));

        service.rebuildViews();

        ArgumentCaptor<List<ScholardexPublicationView>> publicationViewsCaptor = ArgumentCaptor.forClass(List.class);
        verify(publicationViewRepository).saveAll(publicationViewsCaptor.capture());
        assertEquals(1, publicationViewsCaptor.getValue().size());
        assertEquals("WOS:0001", publicationViewsCaptor.getValue().getFirst().getWosId());
    }
}
