package ro.uvt.pokedex.core.service.openalex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
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
        when(authorRepository.findByOrcidIdsContains("0000-0002-0702-6276")).thenReturn(Optional.of(existing));

        String id = resolver.resolveOrMint(ref("Me", "0000-0002-0702-6276", "A123"), "batch", "corr");

        assertEquals("sauth_scopus", id);
        // Found by ORCID; the OpenAlex id is enriched onto the existing author, no new mint.
        verify(authorRepository, never()).findById(any());
    }

    @Test
    void mintsWhenNoIdKeyMatches() {
        when(authorRepository.findByOrcidIdsContains(any())).thenReturn(Optional.empty());
        when(authorRepository.findByOpenAlexAuthorIdsContains(any())).thenReturn(Optional.empty());
        when(authorRepository.findById(any())).thenReturn(Optional.empty());

        String id = resolver.resolveOrMint(ref("New Person", "0000-0001-2345-6789", "A999"), "batch", "corr");

        assertTrue(id != null && id.startsWith("sauth_"));
        verify(authorRepository).save(argThat(a ->
                a.getOrcidIds().contains("0000-0001-2345-6789")
                        && a.getOpenAlexAuthorIds().contains("A999")
                        && "New Person".equals(a.getDisplayName())
                        && "OPENALEX".equals(a.getSource())));
    }

    @Test
    void nameOnlyRefIsNotIdResolvable() {
        assertNull(resolver.resolveOrMint(ref("Anon", null, null), "batch", "corr"));
        verify(authorRepository, never()).save(any());
    }

    @Test
    void attachOrcidSeedsAnExistingAuthorIdempotently() {
        ScholardexAuthorFact scopusAuthor = author("sauth_scopus", null);
        when(authorRepository.findById("sauth_scopus")).thenReturn(Optional.of(scopusAuthor));

        resolver.attachOrcid("sauth_scopus", "0000-0002-0702-6276");

        verify(authorRepository).save(argThat(a -> a.getOrcidIds().contains("0000-0002-0702-6276")));
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

    private OpenAlexPublicationFact.CorrespondingAuthorRef ref(String name, String orcid, String openAlexId) {
        OpenAlexPublicationFact.CorrespondingAuthorRef ref = new OpenAlexPublicationFact.CorrespondingAuthorRef();
        ref.setDisplayName(name);
        ref.setOrcid(orcid);
        ref.setOpenAlexAuthorId(openAlexId);
        return ref;
    }
}
