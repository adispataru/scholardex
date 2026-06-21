package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexAffiliationRorBridgeServiceTest {

    @Mock private OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    @Mock private ScholardexPublicationFactRepository publicationRepository;
    @Mock private ScholardexAuthorFactRepository authorRepository;
    @Mock private ScholardexAffiliationFactRepository affiliationRepository;
    @Mock private ScholardexPublicationAuthorAffiliationFactRepository pubAuthorAffiliationRepository;
    @InjectMocks private ScholardexAffiliationRorBridgeService service;

    @Test
    void bridgesRorOntoTheVerifiedScopusAffiliationOnADoiLinkedPub() {
        OpenAlexPublicationFact src = source("10.1/x", ref("Bogdan Tulbure", "03r6neh61"));
        ScholardexPublicationFact pub = scopusPub("spub1", "sauth1");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(src));
        when(publicationRepository.findAllByDoiNormalized(anyString())).thenReturn(List.of(pub));
        when(authorRepository.findByIdIn(List.of("sauth1"))).thenReturn(List.of(author("sauth1", "Tulbure, Bogdan T.")));
        when(pubAuthorAffiliationRepository.findByPublicationIdAndAuthorIdIn("spub1", List.of("sauth1")))
                .thenReturn(List.of(edge("saff_uvt")));
        ScholardexAffiliationFact uvt = affiliation("saff_uvt");
        when(affiliationRepository.findById("saff_uvt")).thenReturn(Optional.of(uvt));

        int tagged = service.bridgeRorsFromOpenAlex("test");

        assertEquals(1, tagged);
        verify(affiliationRepository).save(argThat(a -> "saff_uvt".equals(a.getId()) && a.getRorIds().contains("03r6neh61")));
    }

    @Test
    void skipsOpenAlexOwnedPubs() {
        OpenAlexPublicationFact src = source("10.1/x", ref("Bogdan Tulbure", "03r6neh61"));
        ScholardexPublicationFact owned = scopusPub("spub1", "sauth1");
        owned.setSource("OPENALEX");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(src));
        when(publicationRepository.findAllByDoiNormalized(anyString())).thenReturn(List.of(owned));

        assertEquals(0, service.bridgeRorsFromOpenAlex("test"));
        verify(affiliationRepository, never()).save(any());
    }

    @Test
    void skipsWhenAuthorCountDiffers() {
        OpenAlexPublicationFact src = source("10.1/x", ref("Bogdan Tulbure", "03r6neh61"));
        ScholardexPublicationFact pub = scopusPub("spub1", "sauth1", "sauth2"); // 2 authors vs 1 authorship
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(src));
        when(publicationRepository.findAllByDoiNormalized(anyString())).thenReturn(List.of(pub));

        assertEquals(0, service.bridgeRorsFromOpenAlex("test"));
        verify(affiliationRepository, never()).save(any());
    }

    @Test
    void skipsWhenAuthorshipHasMoreThanOneRorOrAuthorHasMoreThanOneAffiliation() {
        // two RORs on the authorship => can't align which ROR ↔ which affiliation => skip
        OpenAlexPublicationFact src = source("10.1/x", ref("Bogdan Tulbure", "03r6neh61", "0111es613"));
        ScholardexPublicationFact pub = scopusPub("spub1", "sauth1");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(src));
        when(publicationRepository.findAllByDoiNormalized(anyString())).thenReturn(List.of(pub));
        when(authorRepository.findByIdIn(List.of("sauth1"))).thenReturn(List.of(author("sauth1", "Tulbure, Bogdan T.")));

        assertEquals(0, service.bridgeRorsFromOpenAlex("test"));
        verify(affiliationRepository, never()).save(any());
    }

    @Test
    void skipsWhenSurnameDoesNotMatch() {
        OpenAlexPublicationFact src = source("10.1/x", ref("Jane Doe", "03r6neh61"));
        ScholardexPublicationFact pub = scopusPub("spub1", "sauth1");
        when(openAlexPublicationFactRepository.findAll()).thenReturn(List.of(src));
        when(publicationRepository.findAllByDoiNormalized(anyString())).thenReturn(List.of(pub));
        when(authorRepository.findByIdIn(List.of("sauth1"))).thenReturn(List.of(author("sauth1", "Tulbure, Bogdan T.")));

        assertEquals(0, service.bridgeRorsFromOpenAlex("test"));
        verify(affiliationRepository, never()).save(any());
    }

    // ── helpers ──
    private OpenAlexPublicationFact source(String doi, OpenAlexPublicationFact.AuthorRef... refs) {
        OpenAlexPublicationFact f = new OpenAlexPublicationFact();
        f.setDoi(doi);
        f.setAuthorships(new ArrayList<>(List.of(refs)));
        return f;
    }
    private OpenAlexPublicationFact.AuthorRef ref(String name, String... rors) {
        OpenAlexPublicationFact.AuthorRef r = new OpenAlexPublicationFact.AuthorRef();
        r.setDisplayName(name);
        r.setInstitutionRors(new ArrayList<>(List.of(rors)));
        return r;
    }
    private ScholardexPublicationFact scopusPub(String id, String... authorIds) {
        ScholardexPublicationFact p = new ScholardexPublicationFact();
        p.setId(id);
        p.setSource("SCOPUS_JSON_BOOTSTRAP");
        p.setDoiNormalized("10.1/x");
        p.setAuthorIds(new ArrayList<>(List.of(authorIds)));
        return p;
    }
    private ScholardexAuthorFact author(String id, String name) {
        ScholardexAuthorFact a = new ScholardexAuthorFact();
        a.setId(id);
        a.setDisplayName(name);
        return a;
    }
    private ScholardexPublicationAuthorAffiliationFact edge(String affiliationId) {
        ScholardexPublicationAuthorAffiliationFact e = new ScholardexPublicationAuthorAffiliationFact();
        e.setAffiliationId(affiliationId);
        return e;
    }
    private ScholardexAffiliationFact affiliation(String id) {
        ScholardexAffiliationFact a = new ScholardexAffiliationFact();
        a.setId(id);
        a.setRorIds(new ArrayList<>());
        return a;
    }
}
