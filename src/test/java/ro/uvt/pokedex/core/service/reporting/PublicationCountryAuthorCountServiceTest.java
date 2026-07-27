package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicationCountryAuthorCountServiceTest {

    private final ScholardexPublicationAuthorAffiliationFactRepository linkRepository =
            mock(ScholardexPublicationAuthorAffiliationFactRepository.class);
    private final ScholardexAffiliationFactRepository affiliationRepository =
            mock(ScholardexAffiliationFactRepository.class);
    private final PublicationCountryAuthorCountService service =
            new PublicationCountryAuthorCountService(linkRepository, affiliationRepository);

    private static ScoringPublication pub(String id, List<String> authors) {
        return new ScoringPublication(id, null, "f-1", "2023-01-01", "ar", "ar",
                authors, authors.size(), null, null, "T-" + id, 0, Set.of());
    }

    private static ScholardexPublicationAuthorAffiliationFact link(String pub, String author, String affiliation) {
        ScholardexPublicationAuthorAffiliationFact fact = new ScholardexPublicationAuthorAffiliationFact();
        fact.setPublicationId(pub);
        fact.setAuthorId(author);
        fact.setAffiliationId(affiliation);
        return fact;
    }

    private static ScholardexAffiliationFact affiliation(String id, String country) {
        ScholardexAffiliationFact fact = new ScholardexAffiliationFact();
        fact.setId(id);
        fact.setCountry(country);
        return fact;
    }

    @Test
    void subtractsOnlyProvablyForeignAuthors() {
        // 4 authors: a1 RO, a2 foreign-only, a3 mixed (RO + abroad — counts), a4 NO affiliation data (counts).
        when(linkRepository.findByPublicationIdIn(List.of("p-1"))).thenReturn(List.of(
                link("p-1", "a1", "aff-ro"),
                link("p-1", "a2", "aff-us"),
                link("p-1", "a3", "aff-ro"),
                link("p-1", "a3", "aff-us")));
        when(affiliationRepository.findByIdIn(anyCollection())).thenReturn(List.of(
                affiliation("aff-ro", "Romania"),
                affiliation("aff-us", "United States")));

        assertEquals(3, service.authorCountForCountry(pub("p-1", List.of("a1", "a2", "a3", "a4")), "Romania"));
    }

    @Test
    void noAffiliationRowsFallsBackToTotalAuthorCount() {
        when(linkRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        assertEquals(5, service.authorCountForCountry(pub("p-2", List.of("a1", "a2", "a3", "a4", "a5")), "Romania"));
    }

    @Test
    void neverDropsBelowOne() {
        // Both authors provably foreign (data glitch on the candidate) — clamp at 1, never 0/negative.
        when(linkRepository.findByPublicationIdIn(List.of("p-3"))).thenReturn(List.of(
                link("p-3", "a1", "aff-us"),
                link("p-3", "a2", "aff-us")));
        when(affiliationRepository.findByIdIn(anyCollection())).thenReturn(List.of(
                affiliation("aff-us", "United States")));

        assertEquals(1, service.authorCountForCountry(pub("p-3", List.of("a1", "a2")), "Romania"));
    }

    @Test
    void strayLinksForUnknownAuthorsDoNotSubtract() {
        // A link row for an author NOT on the publication's author list must not shrink N.
        when(linkRepository.findByPublicationIdIn(List.of("p-4"))).thenReturn(List.of(
                link("p-4", "ghost", "aff-us")));
        when(affiliationRepository.findByIdIn(anyCollection())).thenReturn(List.of(
                affiliation("aff-us", "United States")));

        assertEquals(2, service.authorCountForCountry(pub("p-4", List.of("a1", "a2")), "Romania"));
    }
}
