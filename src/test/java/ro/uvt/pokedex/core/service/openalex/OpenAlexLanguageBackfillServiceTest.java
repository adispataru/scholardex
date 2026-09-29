package ro.uvt.pokedex.core.service.openalex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAlexLanguageBackfillServiceTest {

    @Mock
    private OpenAlexPublicationFactRepository facts;
    @Mock
    private OpenAlexClient client;
    @Mock
    private OpenAlexSourceCountryService countries;
    @Mock
    private ro.uvt.pokedex.core.service.CacheService cache;
    @Mock
    private ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository publications;
    @Mock
    private ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository links;
    @InjectMocks
    private OpenAlexLanguageBackfillService service;
    @Captor
    private ArgumentCaptor<Collection<String>> venues;

    private static OpenAlexPublicationFact fact(String workId, String venue) {
        OpenAlexPublicationFact fact = new OpenAlexPublicationFact();
        fact.setSourceRecordId(workId);
        fact.setOpenalexWorkId(workId);
        fact.setHostVenueOpenAlexId(venue);
        return fact;
    }

    private static OpenAlexWorksResponse.OpenAlexWork work(String id, String language, String venue) {
        OpenAlexWorksResponse.OpenAlexWork work = new OpenAlexWorksResponse.OpenAlexWork();
        work.setId("https://openalex.org/" + id);
        work.setLanguage(language);
        if (venue != null) {
            OpenAlexWorksResponse.OpenAlexSource source = new OpenAlexWorksResponse.OpenAlexSource();
            source.setId("https://openalex.org/" + venue);
            OpenAlexWorksResponse.PrimaryLocation location = new OpenAlexWorksResponse.PrimaryLocation();
            location.setSource(source);
            work.setPrimary_location(location);
        }
        return work;
    }

    @Test
    void fillsInTheLanguageAndAsksWhereTheVenuesArePublished() {
        OpenAlexPublicationFact english = fact("W1", "S1");
        OpenAlexPublicationFact silent = fact("W2", "S2");   // OpenAlex returns it without a language
        OpenAlexPublicationFact gone = fact("W3", null);     // OpenAlex does not return it at all
        when(facts.findSyncedWithoutLanguage(PageRequest.of(0, 500))).thenReturn(List.of(english, silent, gone));
        when(client.fetchWorkLanguages(anyCollection()))
                .thenReturn(List.of(work("W1", "EN", "S1"), work("W2", null, "S9")));
        when(countries.resolve(anyCollection())).thenReturn(new OpenAlexSourceCountryService.Result(3, 3, 2));

        OpenAlexLanguageBackfillService.Result result = service.backfill(500);

        assertEquals(3, result.candidates());
        assertEquals(1, result.withLanguage());
        assertEquals(2, result.withoutLanguage());
        assertEquals(3, result.venuesAsked());
        assertEquals(2, result.venuesWithCountry());
        assertEquals("en", english.getLanguage());
        // Marked, so that the next run does not ask about them again.
        assertEquals(OpenAlexLanguageBackfillService.UNKNOWN, silent.getLanguage());
        assertEquals(OpenAlexLanguageBackfillService.UNKNOWN, gone.getLanguage());
        verify(facts).saveAll(anyCollection());
        verify(countries).resolve(venues.capture());
        assertEquals(Set.of("S1", "S2", "S9"), Set.copyOf(venues.getValue()));
    }

    @Test
    void withNothingToFillInNothingIsAsked() {
        when(facts.findSyncedWithoutLanguage(any())).thenReturn(List.of());

        assertEquals(0, service.backfill(100).candidates());
        verify(client, never()).fetchWorkLanguages(anyCollection());
        verify(countries, never()).resolve(anyCollection());
    }

    @Test
    void theSizeOfOnePassIsBounded() {
        when(facts.findSyncedWithoutLanguage(any())).thenReturn(List.of());

        service.backfill(0);
        service.backfill(1_000_000);

        verify(facts).findSyncedWithoutLanguage(PageRequest.of(0, 1));
        verify(facts).findSyncedWithoutLanguage(PageRequest.of(0, 5000));
    }

    // ------------------------------------------------------------------ works nobody synced personally

    private static ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact publication(String id) {
        var publication = new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact();
        publication.setId(id);
        return publication;
    }

    private static ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink link(String publicationId, String workId) {
        var link = new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink();
        link.setEntityType(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType.PUBLICATION);
        link.setSource("OPENALEX");
        link.setCanonicalEntityId(publicationId);
        link.setSourceRecordId(workId);
        return link;
    }

    private void theUniversityHas(List<String> publicationIds,
                                  List<ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink> openAlexLinks,
                                  List<OpenAlexPublicationFact> works) {
        when(cache.getUniversityAuthorIds()).thenReturn(Set.of("sauth_1", "sauth_2"));
        when(publications.findIdsByAuthorIdsIn(anyCollection()))
                .thenReturn(publicationIds.stream().map(OpenAlexLanguageBackfillServiceTest::publication).toList());
        when(links.findByEntityTypeAndSourceAndCanonicalEntityIdIn(
                org.mockito.ArgumentMatchers.eq(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType.PUBLICATION),
                org.mockito.ArgumentMatchers.eq("OPENALEX"), anyCollection())).thenReturn(openAlexLinks);
        when(facts.findBySourceRecordIdIn(anyCollection())).thenReturn(works);
    }

    @Test
    void coversTheWorksOfTheResearchersThatCameWithTheBulkImport() {
        // Nobody synced these; the researcher sees them, confirms them and is scored on them all the same.
        OpenAlexPublicationFact bulk = fact("W10", "S10");
        OpenAlexPublicationFact alreadyKnown = fact("W11", "S11");
        alreadyKnown.setLanguage("ro");
        OpenAlexPublicationFact marked = fact("W12", "S12");
        marked.setLanguage(OpenAlexLanguageBackfillService.UNKNOWN);
        when(facts.findSyncedWithoutLanguage(any())).thenReturn(List.of());
        theUniversityHas(List.of("p10", "p11", "p12"),
                List.of(link("p10", "W10"), link("p11", "W11"), link("p12", "W12")),
                List.of(bulk, alreadyKnown, marked));
        when(client.fetchWorkLanguages(anyCollection())).thenReturn(List.of(work("W10", "fr", "S10")));
        when(countries.resolve(anyCollection())).thenReturn(new OpenAlexSourceCountryService.Result(1, 1, 1));

        OpenAlexLanguageBackfillService.Result result = service.backfill(500);

        assertEquals(1, result.candidates(), "a work that has a language, or was marked, is not asked about");
        assertEquals(1, result.withLanguage());
        assertEquals("fr", bulk.getLanguage());
        assertEquals("ro", alreadyKnown.getLanguage());
        verify(countries).resolve(venues.capture());
        assertEquals(Set.of("S10"), Set.copyOf(venues.getValue()));
    }

    @Test
    void aWorkSomebodySyncedIsNotTakenTwice() {
        OpenAlexPublicationFact synced = fact("W1", "S1");
        when(facts.findSyncedWithoutLanguage(any())).thenReturn(List.of(synced));
        theUniversityHas(List.of("p1", "p2"), List.of(link("p1", "W1"), link("p2", "W2")), List.of(fact("W2", "S2")));
        when(client.fetchWorkLanguages(anyCollection()))
                .thenReturn(List.of(work("W1", "en", "S1"), work("W2", "en", "S2")));
        when(countries.resolve(anyCollection())).thenReturn(new OpenAlexSourceCountryService.Result(2, 2, 2));

        OpenAlexLanguageBackfillService.Result result = service.backfill(500);

        assertEquals(2, result.candidates());
        // W1 came with the synced works; only W2 is looked up through the links.
        verify(facts).findBySourceRecordIdIn(List.of("W2"));
    }

    @Test
    void aPassTakesNoMoreThanItsLimit() {
        when(facts.findSyncedWithoutLanguage(any())).thenReturn(List.of());
        theUniversityHas(List.of("p1", "p2", "p3"),
                List.of(link("p1", "W1"), link("p2", "W2"), link("p3", "W3")),
                List.of(fact("W1", null), fact("W2", null), fact("W3", null)));
        when(client.fetchWorkLanguages(anyCollection())).thenReturn(List.of());
        when(countries.resolve(anyCollection())).thenReturn(new OpenAlexSourceCountryService.Result(0, 0, 0));

        assertEquals(2, service.backfill(2).candidates());
    }

    @Test
    void aPublicationWithoutAnOpenAlexRecordIsSkipped() {
        when(facts.findSyncedWithoutLanguage(any())).thenReturn(List.of());
        when(cache.getUniversityAuthorIds()).thenReturn(Set.of("sauth_1"));
        when(publications.findIdsByAuthorIdsIn(anyCollection())).thenReturn(List.of(publication("p-scopus-only")));
        when(links.findByEntityTypeAndSourceAndCanonicalEntityIdIn(any(), any(), anyCollection())).thenReturn(List.of());

        assertEquals(0, service.backfill(100).candidates());
        verify(facts, never()).findBySourceRecordIdIn(anyCollection());
        verify(client, never()).fetchWorkLanguages(anyCollection());
    }
}
