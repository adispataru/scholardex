package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearcherAuthorLookupServiceTest {

    private final ScholardexAuthorFactRepository authorFactRepository = mock(ScholardexAuthorFactRepository.class);
    private final ResearcherAuthorLookupService service = new ResearcherAuthorLookupService(authorFactRepository);

    @Test
    void resolveAuthorLookupKeysReturnsEmptyWhenProfileIsNull() {
        assertTrue(service.resolveAuthorLookupKeys(null).isEmpty());
    }

    @Test
    void resolveAuthorLookupKeysBuildsNormalizedOrderedDistinctKeys() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setPrimaryScholardexAuthorId("  author-1 ");
        profile.setScopusId(List.of("  sc-1 ", "sc-2", "author-1", " "));
        profile.setWosId(List.of(" wos-1 ", "sc-2", "", "wos-2"));
        profile.setScholarId("  scholar-1 ");

        List<String> keys = service.resolveAuthorLookupKeys(profile);

        assertEquals(List.of("author-1", "sc-1", "sc-2", "wos-1", "wos-2", "scholar-1"), keys);
    }

    @Test
    void resolveAuthorLookupKeysIncludesConfirmedScholardexAuthorIdsAfterPrimary() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setPrimaryScholardexAuthorId("author-primary");
        profile.setConfirmedScholardexAuthorIds(List.of("author-primary", " author-2 ", "author-3"));
        profile.setScopusId(List.of("sc-1"));

        List<String> keys = service.resolveAuthorLookupKeys(profile);

        // primary first, then the confirmed set (deduped against primary), then source ids
        assertEquals(List.of("author-primary", "author-2", "author-3", "sc-1"), keys);
    }

    @Test
    void resolveAuthorLookupKeysSkipsNullCollectionsAndBlankValues() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setPrimaryScholardexAuthorId(" ");
        profile.setScopusId(null);
        profile.setWosId(null);
        profile.setScholarId(null);

        List<String> keys = service.resolveAuthorLookupKeys(profile);
        assertTrue(keys.isEmpty());
    }

    @Test
    void orcidOnlyProfileResolvesCanonicalAuthorIdsThroughAuthorFacts() {
        // The reported case: a user enters ONLY their ORCID — the author record carrying that ORCID
        // must surface as a lookup key even though source_links has no ORCID rows.
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setOrcid("0000-0003-4039-385X");
        ScholardexAuthorFact fact = new ScholardexAuthorFact();
        fact.setId("sauth_orcid_match");
        when(authorFactRepository.findByOrcidIdsContains("0000-0003-4039-385X")).thenReturn(List.of(fact));

        assertEquals(List.of("sauth_orcid_match"), service.resolveAuthorLookupKeys(profile));
    }

    @Test
    void orcidIsNormalizedFromUrlFormBeforeTheAuthorFactLookup() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setOrcid("https://orcid.org/0000-0003-4039-385x");
        ScholardexAuthorFact fact = new ScholardexAuthorFact();
        fact.setId("sauth_orcid_match");
        when(authorFactRepository.findByOrcidIdsContains("0000-0003-4039-385X")).thenReturn(List.of(fact));

        assertEquals(List.of("sauth_orcid_match"), service.resolveAuthorLookupKeys(profile));
    }

    @Test
    void orcidResolvedAuthorsDedupeAgainstAlreadyConfirmedIds() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setPrimaryScholardexAuthorId("sauth_1");
        profile.setOrcid("0000-0002-0702-6276");
        ScholardexAuthorFact fact = new ScholardexAuthorFact();
        fact.setId("sauth_1");
        lenient().when(authorFactRepository.findByOrcidIdsContains("0000-0002-0702-6276")).thenReturn(List.of(fact));

        assertEquals(List.of("sauth_1"), service.resolveAuthorLookupKeys(profile));
    }

    @Test
    void blankOrUnparseableOrcidSkipsTheAuthorFactLookup() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setScopusId(List.of("sc-1"));
        profile.setOrcid("not-an-orcid");

        assertEquals(List.of("sc-1"), service.resolveAuthorLookupKeys(profile));
        verify(authorFactRepository, never()).findByOrcidIdsContains(anyString());
    }
}
