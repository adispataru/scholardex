package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.PsihologiePublisher;
import ro.uvt.pokedex.core.repository.reporting.PsihologiePublisherRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PsihologiePublisherServiceTest {

    private final PsihologiePublisherRepository repository = mock(PsihologiePublisherRepository.class);

    private PsihologiePublisherService serviceWith(PsihologiePublisher... rows) {
        when(repository.count()).thenReturn((long) rows.length);
        when(repository.findAll()).thenReturn(List.of(rows));
        return new PsihologiePublisherService(repository);
    }

    private static PsihologiePublisher pub(String name, String tier) {
        PsihologiePublisher p = new PsihologiePublisher();
        p.setName(name);
        p.setTier(tier);
        return p;
    }

    @Test
    void exactNameMatchesTier() {
        PsihologiePublisherService s = serviceWith(pub("Editura Polirom", "A2"), pub("Editura All", "B"));
        assertEquals("A2", s.tierFor("Editura Polirom"));
        assertEquals("B", s.tierFor("Editura All"));
    }

    @Test
    void diacriticsAndCaseAreNormalized() {
        PsihologiePublisherService s = serviceWith(pub("Presa Universitara Clujeana", "B"));
        assertEquals("B", s.tierFor("Presa Universitară Clujeană"));
        assertEquals("B", s.tierFor("PRESA UNIVERSITARA CLUJEANA"));
    }

    @Test
    void containmentMatchesWhenActualNameHasExtraLocationTokens() {
        PsihologiePublisherService s = serviceWith(pub("Editura Trei", "A2"));
        assertEquals("A2", s.tierFor("Editura Trei Bucuresti"));
    }

    @Test
    void unlistedPublisherReturnsNull() {
        PsihologiePublisherService s = serviceWith(pub("Editura Polirom", "A2"));
        assertNull(s.tierFor("Cambridge University Press"));
    }

    @Test
    void nullOrBlankPublisherReturnsNull() {
        PsihologiePublisherService s = serviceWith(pub("Editura Polirom", "A2"));
        assertNull(s.tierFor(null));
        assertNull(s.tierFor("   "));
    }

    @Test
    void longerListedNameWinsToAvoidShadowing() {
        // A short national name must not shadow a more specific listed publisher that also matches.
        PsihologiePublisherService s = serviceWith(
                pub("Editura Universitara", "B"),
                pub("Editura Universitatii de Vest", "B"));
        assertEquals("B", s.tierFor("Editura Universitatii de Vest Timisoara"));
    }

    // ── 2026 lists (OM 3019/2025, Comisia 28) — read from the classpath, independent of Mongo ──

    @Test
    void the2026ListKeepsTheA2PublishersAndAddsTheNewTierBOnes() {
        PsihologiePublisherService s = serviceWith(pub("Editura All", "B"));
        assertEquals("A2", s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Editura Polirom, Iași"));
        assertEquals("A2", s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "ASCR"));
        assertEquals("B", s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Humanitas"));
        assertEquals("B", s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Editura Pro Universitaria"));
        assertEquals("B", s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Editura Universității Transilvania din Brașov"));
        assertEquals("B", s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Editura Didactică și Pedagogică R.A."));
    }

    @Test
    void the2026ListNoLongerCarriesAllAndTritonic() {
        PsihologiePublisherService s = serviceWith(pub("Editura All", "B"), pub("Tritonic", "B"));
        assertNull(s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Editura All"));
        assertNull(s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Tritonic"));
        // …while the 2016 list, which the frozen 2016 report scores with, is untouched.
        assertEquals("B", s.tierFor("Editura All"));
        assertEquals("B", s.tierFor("Tritonic"));
    }

    @Test
    void the2026ListHasNoA1AndIgnoresBlankNames() {
        PsihologiePublisherService s = serviceWith();
        assertNull(s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Cambridge University Press"));
        assertNull(s.tierFor2026(Comisia28Rules.PSIHOLOGIE, null));
        assertNull(s.tierFor2026(Comisia28Rules.PSIHOLOGIE, "  "));
    }
}
