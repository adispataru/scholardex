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

    @Mock private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock private ScholardexAuthorFactRepository scholardexAuthorFactRepository;

    private ResearcherAuthorLookupService lookupService;
    private ProfileLinkedAuthorResolutionService service;

    @org.junit.jupiter.api.BeforeEach
    void wire() {
        lookupService = new ResearcherAuthorLookupService(scholardexAuthorFactRepository);
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
        // The lookup service now resolves the ORCID into a key itself, so the projection read IS
        // queried with the resolved canonical id (and the local ORCID pass dedupes on top).
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_orcid")))
                .thenReturn(List.of(author("sauth_orcid")));

        assertEquals(List.of("sauth_orcid"), service.resolveCanonicalAuthorIds(profile));
    }

    @Test
    void unionsKeyAndOrcidResolutionsAndDedupes() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setScopusId(new java.util.ArrayList<>(List.of("55555")));
        profile.setOrcid("0000-0002-1825-0097");
        // ORCID transiently on two facts, one overlapping the key resolution.
        when(scholardexAuthorFactRepository.findByOrcidIdsContains("0000-0002-1825-0097"))
                .thenReturn(List.of(fact("sauth_2"), fact("sauth_3")));
        // Lookup keys now carry the scopus id plus the ORCID-resolved canonical ids.
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("55555", "sauth_2", "sauth_3")))
                .thenReturn(List.of(author("sauth_1"), author("sauth_2"), author("sauth_3")));

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

    @org.junit.jupiter.api.Test
    void firstOpenAlexIdMapSkipsAuthorsWithoutOpenAlexKeysAndBatchesOneRead() {
        ScholardexAuthorFact withKey = fact("sauth_1");
        withKey.setOpenAlexAuthorIds(new java.util.ArrayList<>(java.util.List.of("A111", "A222")));
        ScholardexAuthorFact withoutKey = fact("sauth_2");
        when(scholardexAuthorFactRepository.findByIdIn(any()))
                .thenReturn(java.util.List.of(withKey, withoutKey));

        var map = service.firstOpenAlexAuthorIdByCanonicalIds(java.util.List.of("sauth_1", "sauth_2"));

        assertEquals(java.util.Map.of("sauth_1", "A111"), map);
        assertTrue(service.firstOpenAlexAuthorIdByCanonicalIds(java.util.List.of()).isEmpty());
        verify(scholardexAuthorFactRepository, org.mockito.Mockito.times(1)).findByIdIn(any());
    }
}
