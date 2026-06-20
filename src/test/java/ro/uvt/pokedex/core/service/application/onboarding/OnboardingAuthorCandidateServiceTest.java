package ro.uvt.pokedex.core.service.application.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingAuthorCandidateServiceTest {

    @Mock private ResearcherAuthorLookupService lookup;
    @Mock private ScholardexProjectionReadService reads;
    @InjectMocks private OnboardingAuthorCandidateService service;

    @Test
    void nullProfileYieldsNoCandidates() {
        assertTrue(service.candidatesFor(null).isEmpty());
    }

    @Test
    void noLookupKeysYieldsNoCandidates() {
        when(lookup.resolveAuthorLookupKeys(any())).thenReturn(List.of());
        assertTrue(service.candidatesFor(new User.ResearcherProfile()).isEmpty());
    }

    @Test
    void buildsCandidatesWithPubCountAndAffiliationsSortedByPubCountDesc() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setScopusId(List.of("sc-1"));
        profile.setConfirmedScholardexAuthorIds(List.of("a2"));   // a2 already confirmed

        when(lookup.resolveAuthorLookupKeys(profile)).thenReturn(List.of("sc-1"));
        when(reads.findAuthorsByIdIn(List.of("sc-1")))
                .thenReturn(List.of(author("a1", "Petcu, Dana", "aff-1"), author("a2", "Dana, Petcu", "aff-2")));
        when(reads.countPublicationsByAuthor("a1")).thenReturn(3);
        when(reads.countPublicationsByAuthor("a2")).thenReturn(160);
        when(reads.findAffiliationsByIdIn(List.of("aff-1")))
                .thenReturn(List.of(affiliation("UVT", "Timisoara", "Romania")));
        when(reads.findAffiliationsByIdIn(List.of("aff-2")))
                .thenReturn(List.of(affiliation("West University", null, null)));

        List<AuthorCandidate> result = service.candidatesFor(profile);

        assertEquals(2, result.size());
        // most-published first
        assertEquals("a2", result.get(0).authorId());
        assertEquals(160, result.get(0).publicationCount());
        assertTrue(result.get(0).alreadyConfirmed());
        assertEquals(List.of("West University"), result.get(0).affiliations());
        // second
        assertEquals("a1", result.get(1).authorId());
        assertFalse(result.get(1).alreadyConfirmed());
        assertEquals(List.of("UVT (Timisoara, Romania)"), result.get(1).affiliations());
    }

    @Test
    void deduplicatesRepeatedAuthorIds() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        when(lookup.resolveAuthorLookupKeys(profile)).thenReturn(List.of("sc-1", "sc-2"));
        when(reads.findAuthorsByIdIn(any()))
                .thenReturn(List.of(author("a1", "X", null), author("a1", "X", null)));
        lenient().when(reads.countPublicationsByAuthor("a1")).thenReturn(5);

        List<AuthorCandidate> result = service.candidatesFor(profile);
        assertEquals(1, result.size());
        assertEquals("a1", result.get(0).authorId());
    }

    private static ScholardexAuthorView author(String id, String name, String affiliationId) {
        ScholardexAuthorView v = new ScholardexAuthorView();
        v.setId(id);
        v.setName(name);
        if (affiliationId != null) v.setAffiliationIds(List.of(affiliationId));
        return v;
    }

    private static ScholardexAffiliationView affiliation(String name, String city, String country) {
        ScholardexAffiliationView a = new ScholardexAffiliationView();
        a.setName(name);
        a.setCity(city);
        a.setCountry(country);
        return a;
    }
}
