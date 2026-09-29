package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.openalex.OpenAlexSourceCountryService;
import ro.uvt.pokedex.core.service.reporting.PublicationCoefficientSupport.Coefficient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OM 3.019/2025, Comisia 25, definition [6] — the coefficient m from language and place of publication. */
@ExtendWith(MockitoExtension.class)
class PublicationCoefficientServiceTest {

    @Mock
    private ScholardexSourceLinkRepository links;
    @Mock
    private OpenAlexPublicationFactRepository facts;
    @Mock
    private OpenAlexSourceCountryService countries;

    private PublicationCoefficientService service;

    @BeforeEach
    void setUp() {
        service = new PublicationCoefficientService(links, facts, countries);
    }

    private static ScoringPublication publication(String id) {
        return new ScoringPublication(id, null, "forum-1", "2023-01-01", "ar", null,
                List.of("a1"), 1, "10.1000/" + id, null, "Title", 0, Set.of());
    }

    private static ScholardexSourceLink link(String source, String recordId) {
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setEntityType(ScholardexEntityType.PUBLICATION);
        link.setSource(source);
        link.setSourceRecordId(recordId);
        return link;
    }

    private static OpenAlexPublicationFact work(String id, String language, String venue) {
        OpenAlexPublicationFact fact = new OpenAlexPublicationFact();
        fact.setSourceRecordId(id);
        fact.setOpenalexWorkId(id);
        fact.setLanguage(language);
        fact.setHostVenueOpenAlexId(venue);
        return fact;
    }

    /** One publication, known to OpenAlex as one work, in the given language and venue. */
    private Coefficient resolve(String language, String venue, String venueCountry) {
        when(links.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, "p1"))
                .thenReturn(List.of(link("SCOPUS", "2-s2.0-1"), link("OPENALEX", "W1")));
        when(facts.findBySourceRecordIdIn(List.of("W1"))).thenReturn(List.of(work("W1", language, venue)));
        lenient().when(countries.countryOf(venue)).thenReturn(Optional.ofNullable(venueCountry));
        return service.resolve(publication("p1"));
    }

    @Test
    void anInternationalLanguageAtAVenuePublishedAbroadIsWorthTwo() {
        Coefficient coefficient = resolve("en", "S1", "GB");

        assertEquals(2.0, coefficient.value());
        assertEquals(PublicationCoefficientService.ABROAD, coefficient.basis());
    }

    @Test
    void everyLanguageTheAnnexNamesCounts() {
        for (String language : List.of("en", "fr", "de", "it", "es", "EN")) {
            service.forget();
            assertEquals(2.0, resolve(language, "S1", "NL").value(), language);
        }
    }

    @Test
    void anInternationalLanguageAtAVenuePublishedInRomaniaIsWorthOneAndAHalf() {
        Coefficient coefficient = resolve("en", "S1", "RO");

        assertEquals(1.5, coefficient.value());
        assertEquals(PublicationCoefficientService.INTERNATIONAL_LANGUAGE, coefficient.basis());
    }

    @Test
    void anUnknownPlaceOfPublicationIsNotTakenForAbroad() {
        Coefficient noCountry = resolve("fr", "S1", null);
        assertEquals(1.5, noCountry.value());
        assertEquals(PublicationCoefficientService.PLACE_UNKNOWN, noCountry.basis());

        service.forget();
        Coefficient noVenue = resolve("fr", null, null); // a book: OpenAlex has no venue for it
        assertEquals(1.5, noVenue.value());
        assertEquals(PublicationCoefficientService.PLACE_UNKNOWN, noVenue.basis());
    }

    @Test
    void anyOtherLanguageIsWorthOneWhereverItIsPublished() {
        Coefficient coefficient = resolve("ro", "S1", "DE");

        assertEquals(1.0, coefficient.value());
        assertEquals(PublicationCoefficientService.OTHER_LANGUAGE, coefficient.basis());
        verify(countries, never()).countryOf(any());
    }

    @Test
    void withoutALanguageNothingIsDetermined() {
        for (String language : new String[]{null, "", " ", "und"}) {
            service.forget();
            Coefficient coefficient = resolve(language, "S1", "GB");
            assertEquals(1.0, coefficient.value());
            assertEquals(PublicationCoefficientSupport.NOT_DETERMINED, coefficient.basis());
        }
    }

    @Test
    void aPublicationOpenAlexDoesNotKnowIsNotDetermined() {
        when(links.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, "p1"))
                .thenReturn(List.of(link("SCOPUS", "2-s2.0-1")));

        Coefficient coefficient = service.resolve(publication("p1"));

        assertEquals(PublicationCoefficientSupport.NOT_DETERMINED, coefficient.basis());
        verify(facts, never()).findBySourceRecordIdIn(anyCollection());
    }

    @Test
    void ofTwoRecordsOfOnePublicationTheOneThatSaysMoreWins() {
        when(links.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, "p1"))
                .thenReturn(List.of(link("OPENALEX", "W1"), link("OPENALEX", "W2")));
        when(facts.findBySourceRecordIdIn(List.of("W1", "W2")))
                .thenReturn(new ArrayList<>(List.of(work("W1", null, "S1"), work("W2", "en", "S2"))));
        when(countries.countryOf("S2")).thenReturn(Optional.of("US"));

        assertEquals(2.0, service.resolve(publication("p1")).value());
    }

    @Test
    void oneReportAsksOncePerPublication() {
        resolve("en", "S1", "GB");
        service.resolve(publication("p1"));
        service.resolve(publication("p1"));

        verify(links, times(1)).findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, "p1");
    }

    @Test
    void aFailingLookupGivesTheFloorAndIsAskedAgain() {
        when(links.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, "p1"))
                .thenThrow(new IllegalStateException("database away"))
                .thenReturn(List.of(link("OPENALEX", "W1")));
        when(facts.findBySourceRecordIdIn(List.of("W1"))).thenReturn(List.of(work("W1", "en", "S1")));
        when(countries.countryOf("S1")).thenReturn(Optional.of("GB"));

        assertEquals(PublicationCoefficientSupport.NOT_DETERMINED, service.resolve(publication("p1")).basis());
        assertEquals(2.0, service.resolve(publication("p1")).value(), "the failure was not remembered");
    }

    @Test
    void theKeyTheDrilldownReadsIsTheOneTheEngineWrites() {
        // IndicatorDetailResponseAssembler spells the key out (the view layer may not reference this package).
        assertEquals("coefM", PublicationCoefficientSupport.BASIS_KEY);
    }

    @Test
    void aPublicationWithoutAnIdIsNotLookedUp() {
        assertEquals(PublicationCoefficientSupport.NOT_DETERMINED, service.resolve(publication(null)).basis());
        assertEquals(PublicationCoefficientSupport.NOT_DETERMINED, service.resolve(null).basis());
        verify(links, never()).findByEntityTypeAndCanonicalEntityId(any(), any());
    }

    @Test
    void theServiceIsTheResolverTheFormulaVariableReads() {
        service.register();
        try {
            when(links.findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, "p1"))
                    .thenReturn(List.of(link("OPENALEX", "W1")));
            when(facts.findBySourceRecordIdIn(List.of("W1"))).thenReturn(List.of(work("W1", "de", "S1")));
            when(countries.countryOf("S1")).thenReturn(Optional.of("AT"));

            assertEquals(2.0, PublicationCoefficientSupport.coefficientFor(publication("p1")).value());
        } finally {
            service.unregister();
        }
        assertEquals(PublicationCoefficientSupport.NOT_DETERMINED,
                PublicationCoefficientSupport.coefficientFor(publication("p1")).basis());
    }
}
