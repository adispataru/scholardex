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
}
