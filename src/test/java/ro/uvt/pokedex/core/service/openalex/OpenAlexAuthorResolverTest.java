package ro.uvt.pokedex.core.service.openalex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAlexAuthorResolverTest {

    @Mock private ScholardexAuthorFactRepository authorRepository;
    @InjectMocks private OpenAlexAuthorResolver resolver;

    @Test
    void resolvesByOrcidToAnExistingCanonicalAuthor() {
        ScholardexAuthorFact existing = author("sauth_scopus", "0000-0002-0702-6276");
        when(authorRepository.findByOrcidIdsContains("0000-0002-0702-6276")).thenReturn(List.of(existing));

        String id = resolver.resolveOrMint("Me", "0000-0002-0702-6276", "A123", "batch", "corr");

        assertEquals("sauth_scopus", id);
        // Found by ORCID; the OpenAlex id is enriched onto the existing author, no new mint.
        verify(authorRepository, never()).findById(any());
    }

    @Test
    void mintsWhenNoIdKeyMatches() {
        when(authorRepository.findByOrcidIdsContains(any())).thenReturn(List.of());
        when(authorRepository.findByOpenAlexAuthorIdsContains(any())).thenReturn(List.of());
        when(authorRepository.findById(any())).thenReturn(Optional.empty());

        String id = resolver.resolveOrMint("New Person", "0000-0001-2345-6789", "A999", "batch", "corr");

        assertTrue(id != null && id.startsWith("sauth_"));
        verify(authorRepository).save(argThat(a ->
                a.getOrcidIds().contains("0000-0001-2345-6789")
                        && a.getOpenAlexAuthorIds().contains("A999")
                        && "New Person".equals(a.getDisplayName())
                        && "OPENALEX".equals(a.getSource())));
    }

    @Test
    void resolvePrefersTheEstablishedScopusAuthorWhenAnOrcidIsSharedByMultiple() {
        // The bridge can transiently leave an ORCID on both a Scopus author and an OpenAlex twin (pre-reconcile);
        // resolution must tolerate it (not throw) and prefer the established Scopus author.
        ScholardexAuthorFact openAlexTwin = author("sauth_openalex", "0000-0002-0702-6276"); // no scopusAuthorIds
        ScholardexAuthorFact scopusTwin = author("sauth_scopus", "0000-0002-0702-6276");
        scopusTwin.setScopusAuthorIds(new ArrayList<>(List.of("12345")));
        when(authorRepository.findByOrcidIdsContains("0000-0002-0702-6276"))
                .thenReturn(List.of(openAlexTwin, scopusTwin)); // OpenAlex twin listed first

        String id = resolver.resolveOrMint("Me", "0000-0002-0702-6276", "A1", "batch", "corr");

        assertEquals("sauth_scopus", id);
    }

    @Test
    void nameOnlyRefIsNotIdResolvable() {
        assertNull(resolver.resolveOrMint("Anon", null, null, "batch", "corr"));
        verify(authorRepository, never()).save(any());
    }

    @Test
    void attachOrcidSeedsAnExistingAuthorIdempotently() {
        ScholardexAuthorFact scopusAuthor = author("sauth_scopus", null);
        when(authorRepository.findById("sauth_scopus")).thenReturn(Optional.of(scopusAuthor));

        resolver.attachOrcid("sauth_scopus", "0000-0002-0702-6276");

        verify(authorRepository).save(argThat(a -> a.getOrcidIds().contains("0000-0002-0702-6276")));
    }

    @Test
    void positionalBridgeSeedsOrcidsOntoScopusAuthorsAtMatchingPositions() {
        // Scopus "Last, First" vs OpenAlex "First Last" — same order, diacritics differ; positions 0,1 match,
        // position 2 has no OpenAlex ORCID (skipped). Count guard satisfied (3==3).
        ScholardexAuthorFact a0 = author("sauth_0", null); a0.setDisplayName("Șandric, Ionuț");
        ScholardexAuthorFact a1 = author("sauth_1", null); a1.setDisplayName("Frîncu, Marc Eduard");
        ScholardexAuthorFact a2 = author("sauth_2", null); a2.setDisplayName("Selea, Teodora");
        when(authorRepository.findByIdIn(List.of("sauth_0", "sauth_1", "sauth_2")))
                .thenReturn(List.of(a0, a1, a2));

        int seeded = resolver.bridgeOrcidsByPosition(
                List.of("sauth_0", "sauth_1", "sauth_2"),
                List.of("Ionut Sandric", "Marc Frincu", "Teodora Selea"),
                java.util.Arrays.asList("0000-0002-9292-9479", "0000-0003-1034-8409", null));

        assertEquals(2, seeded);
        verify(authorRepository).save(argThat(a -> "sauth_0".equals(a.getId()) && a.getOrcidIds().contains("0000-0002-9292-9479")));
        verify(authorRepository).save(argThat(a -> "sauth_1".equals(a.getId()) && a.getOrcidIds().contains("0000-0003-1034-8409")));
    }

    @Test
    void positionalBridgeIsSkippedOnAuthorCountMismatch() {
        int seeded = resolver.bridgeOrcidsByPosition(
                List.of("sauth_0", "sauth_1"),
                List.of("Only One"),
                List.of("0000-0002-9292-9479"));
        assertEquals(0, seeded);
        verify(authorRepository, never()).findByIdIn(any());
    }

    @Test
    void positionalBridgeSkipsAPositionWhoseSurnameDisagrees() {
        ScholardexAuthorFact a0 = author("sauth_0", null); a0.setDisplayName("Smith, John");
        when(authorRepository.findByIdIn(List.of("sauth_0"))).thenReturn(List.of(a0));

        int seeded = resolver.bridgeOrcidsByPosition(
                List.of("sauth_0"), List.of("Jane Doe"), List.of("0000-0002-9292-9479"));

        assertEquals(0, seeded);
        verify(authorRepository, never()).save(any());
    }

    @Test
    void surnameMatchesHandlesOrderSwapAndDiacritics() {
        assertTrue(OpenAlexAuthorResolver.surnameMatches("Șandric, Ionuț", "Ionut Sandric"));
        assertTrue(OpenAlexAuthorResolver.surnameMatches("Filelis-Papadopoulos, Christos K.", "Christos K. Filelis‐Papadopoulos"));
        assertTrue(!OpenAlexAuthorResolver.surnameMatches("Smith, John", "Jane Doe"));
    }

    private ScholardexAuthorFact author(String id, String orcid) {
        ScholardexAuthorFact a = new ScholardexAuthorFact();
        a.setId(id);
        a.setOrcidIds(new ArrayList<>());
        a.setOpenAlexAuthorIds(new ArrayList<>());
        if (orcid != null) {
            a.getOrcidIds().add(orcid);
        }
        return a;
    }
}
