package ro.uvt.pokedex.core.service.openalex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexSourceCountry;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexSourceCountryRepository;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexSourcesResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAlexSourceCountryServiceTest {

    @Mock
    private OpenAlexSourceCountryRepository repository;
    @Mock
    private OpenAlexClient client;
    @Captor
    private ArgumentCaptor<List<OpenAlexSourceCountry>> saved;

    private OpenAlexSourceCountryService service;

    @BeforeEach
    void setUp() {
        service = new OpenAlexSourceCountryService(repository, client, 90);
    }

    private static OpenAlexSourceCountry stored(String id, String country, Instant checkedAt) {
        OpenAlexSourceCountry record = new OpenAlexSourceCountry();
        record.setId(id);
        record.setCountryCode(country);
        record.setCheckedAt(checkedAt);
        return record;
    }

    private static OpenAlexSourcesResponse.Source answer(String id, String country) {
        OpenAlexSourcesResponse.Source source = new OpenAlexSourcesResponse.Source();
        source.setId("https://openalex.org/" + id);
        source.setDisplay_name("Venue " + id);
        source.setCountry_code(country);
        return source;
    }

    @Test
    void asksOnlyAboutTheVenuesThatHaveNoAnswerYet() {
        Instant recently = Instant.now().minus(Duration.ofDays(3));
        Instant longAgo = Instant.now().minus(Duration.ofDays(200));
        when(repository.findAllById(anyCollection())).thenReturn(List.of(
                stored("S-known", "GB", longAgo),          // a country is final, however old
                stored("S-none-recent", null, recently),   // "no country", asked the other day
                stored("S-none-old", null, longAgo)));     // "no country", worth asking again
        when(client.fetchSources(List.of("S-none-old", "S-new")))
                .thenReturn(List.of(answer("S-none-old", "ro"), answer("S-new", null)));

        OpenAlexSourceCountryService.Result result = service.resolve(
                Arrays.asList("S-known", "S-none-recent", "S-none-old", "S-new", "S-new", " ", null));

        assertEquals(4, result.requested());
        assertEquals(2, result.asked());
        assertEquals(1, result.withCountry());
        verify(repository).saveAll(saved.capture());
        Map<String, OpenAlexSourceCountry> byId = saved.getValue().stream()
                .collect(Collectors.toMap(OpenAlexSourceCountry::getId, record -> record));
        assertEquals(2, byId.size());
        assertEquals("RO", byId.get("S-none-old").getCountryCode(), "stored upper case");
        assertNull(byId.get("S-new").getCountryCode(), "no country is an answer too");
        assertTrue(byId.get("S-new").getCheckedAt().isAfter(recently));
    }

    @Test
    void aVenueOpenAlexDoesNotReturnIsRememberedAsAsked() {
        when(repository.findAllById(anyCollection())).thenReturn(List.of());
        when(client.fetchSources(List.of("S-gone"))).thenReturn(List.of());

        OpenAlexSourceCountryService.Result result = service.resolve(List.of("S-gone"));

        assertEquals(1, result.asked());
        verify(repository).saveAll(saved.capture());
        assertEquals("S-gone", saved.getValue().getFirst().getId());
        assertNull(saved.getValue().getFirst().getCountryCode());
    }

    @Test
    void nothingToAskMeansNoCall() {
        when(repository.findAllById(anyCollection())).thenReturn(List.of(stored("S1", "DE", Instant.now())));

        assertEquals(0, service.resolve(List.of("S1")).asked());
        assertEquals(0, service.resolve(List.of()).requested());
        assertEquals(0, service.resolve(null).requested());
        verify(client, never()).fetchSources(anyCollection());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void readingACountryNeverCallsOut() {
        when(repository.findById("S1")).thenReturn(Optional.of(stored("S1", "FR", Instant.now())));
        when(repository.findById("S2")).thenReturn(Optional.of(stored("S2", null, Instant.now())));
        when(repository.findById("S3")).thenReturn(Optional.empty());

        assertEquals(Optional.of("FR"), service.countryOf("S1"));
        assertEquals(Optional.empty(), service.countryOf("S2"));
        assertEquals(Optional.empty(), service.countryOf("S3"));
        assertEquals(Optional.empty(), service.countryOf(null));
        assertEquals(Optional.empty(), service.countryOf(" "));
        verify(client, never()).fetchSources(anyCollection());
    }
}
