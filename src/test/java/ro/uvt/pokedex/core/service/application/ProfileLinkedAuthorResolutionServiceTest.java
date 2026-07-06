package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileLinkedAuthorResolutionServiceTest {

    private final ResearcherAuthorLookupService lookupService = new ResearcherAuthorLookupService();
    @Mock private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock private ScholardexAuthorFactRepository scholardexAuthorFactRepository;

    private ProfileLinkedAuthorResolutionService service;

    @org.junit.jupiter.api.BeforeEach
    void wire() {
        service = new ProfileLinkedAuthorResolutionService(
                lookupService, scholardexProjectionReadService, scholardexAuthorFactRepository);
    }

    @Test
    void resolvesCanonicalIdsFromScopusLookupKeys() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setScopusId(new java.util.ArrayList<>(List.of("55555")));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("55555")))
                .thenReturn(List.of(author("sauth_1")));

        assertEquals(List.of("sauth_1"), service.resolveCanonicalAuthorIds(profile));
        verifyNoInteractions(scholardexAuthorFactRepository);
    }

    @Test
    void resolvesFromOrcidAloneWithNormalization() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setOrcid("https://orcid.org/0000-0002-1825-0097");
        when(scholardexAuthorFactRepository.findByOrcidIdsContains("0000-0002-1825-0097"))
                .thenReturn(List.of(fact("sauth_orcid")));

        assertEquals(List.of("sauth_orcid"), service.resolveCanonicalAuthorIds(profile));
        // No lookup keys → the projection read service is never queried.
        verify(scholardexProjectionReadService, never()).findAuthorsByIdIn(any());
    }

    @Test
    void unionsKeyAndOrcidResolutionsAndDedupes() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setScopusId(new java.util.ArrayList<>(List.of("55555")));
        profile.setOrcid("0000-0002-1825-0097");
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("55555")))
                .thenReturn(List.of(author("sauth_1"), author("sauth_2")));
        // ORCID transiently on two facts, one overlapping the key resolution.
        when(scholardexAuthorFactRepository.findByOrcidIdsContains("0000-0002-1825-0097"))
                .thenReturn(List.of(fact("sauth_2"), fact("sauth_3")));

        assertEquals(List.of("sauth_1", "sauth_2", "sauth_3"), service.resolveCanonicalAuthorIds(profile));
    }

    @Test
    void blankOrUnparseableOrcidSkipsTheFactLookup() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setOrcid("  not-an-orcid ");

        assertTrue(service.resolveCanonicalAuthorIds(profile).isEmpty());
        verifyNoInteractions(scholardexAuthorFactRepository);
    }

    @Test
    void emptyOrNullProfileResolvesToNothing() {
        assertTrue(service.resolveCanonicalAuthorIds(null).isEmpty());
        assertTrue(service.resolveCanonicalAuthorIds(new User.ResearcherProfile()).isEmpty());
        verifyNoInteractions(scholardexProjectionReadService, scholardexAuthorFactRepository);
    }

    private static ScholardexAuthorView author(String id) {
        ScholardexAuthorView view = new ScholardexAuthorView() { };
        view.setId(id);
        return view;
    }

    private static ScholardexAuthorFact fact(String id) {
        ScholardexAuthorFact fact = new ScholardexAuthorFact();
        fact.setId(id);
        return fact;
    }
}
