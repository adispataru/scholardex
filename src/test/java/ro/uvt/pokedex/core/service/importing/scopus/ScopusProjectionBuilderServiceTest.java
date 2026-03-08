package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusProjectionBuilderServiceTest {

    @Mock
    private ScopusForumFactRepository forumFactRepository;
    @Mock
    private ScholardexAuthorFactRepository authorFactRepository;
    @Mock
    private ScholardexAffiliationFactRepository affiliationFactRepository;
    @Mock
    private ScholardexForumFactRepository canonicalForumFactRepository;
    @Mock
    private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock
    private ScopusCitationFactRepository citationFactRepository;
    @Mock
    private ScholardexForumViewRepository forumViewRepository;
    @Mock
    private ScholardexAuthorViewRepository authorViewRepository;
    @Mock
    private ScholardexAffiliationViewRepository affiliationViewRepository;
    @Mock
    private ScholardexPublicationViewRepository publicationViewRepository;

    @Test
    void rebuildViewsPreservesExistingWosEnrichment() {
        ScopusProjectionBuilderService service = new ScopusProjectionBuilderService(
                forumFactRepository,
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                forumViewRepository,
                authorViewRepository,
                affiliationViewRepository,
                publicationViewRepository
        );

        ScholardexPublicationFact publicationFact = new ScholardexPublicationFact();
        publicationFact.setId("p1");
        publicationFact.setEid("2-s2.0-1");
        publicationFact.setTitle("Paper");
        publicationFact.setDoi("https://doi.org/10.1000/AbC");
        publicationFact.setAuthorIds(List.of("a1"));
        publicationFact.setAffiliationIds(List.of("af1"));
        publicationFact.setCitedByCount(1);
        publicationFact.setSourceEventId("ev1");

        when(forumFactRepository.findAll()).thenReturn(List.of());
        when(canonicalForumFactRepository.findAll()).thenReturn(List.of());
        when(authorFactRepository.findAll()).thenReturn(List.of());
        when(affiliationFactRepository.findAll()).thenReturn(List.of());
        when(publicationFactRepository.findAll()).thenReturn(List.of(publicationFact));
        when(citationFactRepository.findAll()).thenReturn(List.of());

        service.rebuildViews();

        ArgumentCaptor<List<ScholardexPublicationView>> publicationViewsCaptor = ArgumentCaptor.forClass(List.class);
        verify(publicationViewRepository).saveAll(publicationViewsCaptor.capture());
        assertEquals(1, publicationViewsCaptor.getValue().size());
        assertNull(publicationViewsCaptor.getValue().getFirst().getWosId());
        assertEquals("10.1000/abc", publicationViewsCaptor.getValue().getFirst().getDoiNormalized());
    }
}
