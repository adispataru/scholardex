package ro.uvt.pokedex.core.service.openalex;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexSourcesResponse;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The language of a work and the country of its venue, from the OpenAlex payload to what the sync stores. */
@ExtendWith(MockitoExtension.class)
class OpenAlexImportLanguageTest {

    private static final String PAGE = """
            {"meta":{"count":2,"next_cursor":null},"results":[
              {"id":"https://openalex.org/W1","doi":"https://doi.org/10.1000/a","title":"A paper",
               "publication_year":2023,"type":"article","language":"en",
               "primary_location":{"source":{"id":"https://openalex.org/S11","display_name":"Journal A",
                 "issn_l":"1111-1111","issn":["1111-1111"],"type":"journal","host_organization_name":"Sage"}}},
              {"id":"https://openalex.org/W2","title":"O lucrare","publication_year":2021,"type":"book-chapter",
               "language":null,"primary_location":null}
            ]}""";

    private static final String SOURCES = """
            {"meta":{"count":1},"results":[
              {"id":"https://openalex.org/S11","display_name":"Journal A","country_code":"GB",
               "host_organization_name":"Sage","works_count":1200}
            ]}""";

    @Mock
    private OpenAlexClient client;
    @Mock
    private OpenAlexPublicationFactRepository facts;
    @Mock
    private ObjectProvider<OpenAlexSourceCountryService> countriesProvider;
    @Mock
    private OpenAlexSourceCountryService countries;
    @Captor
    private ArgumentCaptor<OpenAlexPublicationFact> saved;
    @Captor
    private ArgumentCaptor<Collection<String>> venues;

    private final OpenAlexClient parser = new OpenAlexClient(null, mapper(), "", 200, 200);
    private OpenAlexImportService service;

    private static ObjectMapper mapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @BeforeEach
    void setUp() {
        service = new OpenAlexImportService(client, facts, countriesProvider);
        lenient().when(countriesProvider.getIfAvailable()).thenReturn(countries);
    }

    private List<OpenAlexWorksResponse.OpenAlexWork> works() {
        return parser.parseWorksResponse(PAGE.getBytes(StandardCharsets.UTF_8)).getResults();
    }

    @Test
    void theLanguageIsReadFromThePayload() {
        List<OpenAlexWorksResponse.OpenAlexWork> works = works();

        assertEquals("en", works.get(0).getLanguage());
        assertNull(works.get(1).getLanguage());
    }

    @Test
    void theCountryIsReadFromTheSourceEntity() {
        OpenAlexSourcesResponse response = parser.parseSourcesResponse(SOURCES.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, response.getResults().size());
        assertEquals("https://openalex.org/S11", response.getResults().getFirst().getId());
        assertEquals("GB", response.getResults().getFirst().getCountry_code());
    }

    @Test
    void aSyncStoresTheLanguageAndAsksAboutTheVenues() {
        when(client.fetchWorksByOrcid("0000-0001-0000-0001")).thenReturn(works());
        when(facts.findBySourceRecordId(any())).thenReturn(Optional.empty());

        List<String> ids = service.importByOrcid("0000-0001-0000-0001", "sauth_1", "batch", "batch");

        assertEquals(List.of("W1", "W2"), ids);
        verify(facts, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals("en", saved.getAllValues().get(0).getLanguage());
        assertNull(saved.getAllValues().get(1).getLanguage());
        verify(countries).resolve(venues.capture());
        assertEquals(Set.of("S11"), Set.copyOf(venues.getValue()));
    }

    @Test
    void aPayloadWithoutLanguageDoesNotEraseTheOneStored() {
        OpenAlexPublicationFact existing = new OpenAlexPublicationFact();
        existing.setSourceRecordId("W2");
        existing.setLanguage("ro");
        when(facts.findBySourceRecordId("W2")).thenReturn(Optional.of(existing));

        service.importFullWork(works().get(1), "batch", "batch");

        verify(facts).save(saved.capture());
        assertEquals("ro", saved.getValue().getLanguage());
    }

    @Test
    void aSyncDoesNotFailWhenTheVenuesCannotBeAskedAbout() {
        when(client.fetchWorksByOrcid("0000-0001-0000-0001")).thenReturn(works());
        when(facts.findBySourceRecordId(any())).thenReturn(Optional.empty());
        when(countries.resolve(anyCollection())).thenThrow(new IllegalStateException("OpenAlex away"));

        assertEquals(2, service.importByOrcid("0000-0001-0000-0001", "sauth_1", "batch", "batch").size());
    }

    @Test
    void aSyncWorksWithoutTheCountryService() {
        when(countriesProvider.getIfAvailable()).thenReturn(null);
        when(client.fetchWorksByOrcid("0000-0001-0000-0001")).thenReturn(works());
        when(facts.findBySourceRecordId(any())).thenReturn(Optional.empty());

        assertEquals(2, service.importByOrcid("0000-0001-0000-0001", "sauth_1", "batch", "batch").size());
    }
}
