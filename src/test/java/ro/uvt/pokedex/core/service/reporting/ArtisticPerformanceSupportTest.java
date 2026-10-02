package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.uvt.pokedex.core.model.ArtisticEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H142 — one declared artistic performance for the Music standard and CNFIS Anexa 5.1. */
class ArtisticPerformanceSupportTest {

    @AfterEach
    void resetRegistry() {
        ArtisticEventRankSupport.reset();
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    // ── the CNFIS kind ──

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "declared individual project wins over a conductor's role | Tip=Proiect individual;Rol=Dirijor | INDIVIDUAL",
            "declared group project | Tip=Proiect de grup (2-4) | GROUP",
            "declared collective project | Tip=Proiect colectiv (5+) | COLLECTIVE",
            "a prize, by result | Rezultat=Premiu;Rol=Solist | PRIZE",
            "a nomination, by result | Rezultat=Nominalizare | NOMINATION",
            "a soloist alone on stage, by size | Marime_formatie=1;Rol=Membru într-o formație camerală (până la 10 persoane) | INDIVIDUAL",
            "a trio, by size | Marime_formatie=3 | GROUP",
            "four players are still a group | Marime_formatie=4 | GROUP",
            "five players are a collective | Marime_formatie=5 | COLLECTIVE",
            "a soloist without a size | Rol=Solist | INDIVIDUAL",
            "a composer's premiere | Rol=Compozitor | INDIVIDUAL",
            "a conductor leads a collective | Rol=Dirijor | COLLECTIVE",
            "a stage director | Rol=Regizor | COLLECTIVE",
            "a concertmaster | Rol=Concert-maestru | COLLECTIVE",
            "an orchestra member | Rol=Membru într-un ansamblu de peste 10 persoane | COLLECTIVE",
    })
    void theCnfisKindIsDeclaredOrDerived(String what, String declared, String expected) {
        Map<String, String> f = new HashMap<>();
        for (String pair : declared.split(";")) {
            String[] kv = pair.split("=", 2);
            f.put(kv[0], kv[1]);
        }
        assertEquals(expected, ArtisticPerformanceSupport.cnfisKind(f), what);
    }

    @Test
    void aChamberMusicianWithoutTheEnsembleSizeStaysUndecided() {
        assertNull(ArtisticPerformanceSupport.cnfisKind(fields("Rol", "Membru într-o formație camerală (până la 10 persoane)")));
        assertNull(ArtisticPerformanceSupport.cnfisKind(fields()));
        assertNull(ArtisticPerformanceSupport.cnfisKind(fields("Marime_formatie", "câțiva")));
    }

    // ── the result and the role ──

    @Test
    void theResultIsDeclaredOrReadFromALegacyCnfisKind() {
        assertEquals(ArtisticPerformanceSupport.PRIZE, ArtisticPerformanceSupport.result(fields("Rezultat", "Premiu")));
        assertEquals(ArtisticPerformanceSupport.NOMINATION, ArtisticPerformanceSupport.result(fields("Tip", "Nominalizare individuală")));
        assertEquals(ArtisticPerformanceSupport.PRIZE, ArtisticPerformanceSupport.result(fields("Tip", "Premiu individual")));
        assertEquals(ArtisticPerformanceSupport.PARTICIPATION, ArtisticPerformanceSupport.result(fields("Tip", "Proiect individual")));
        assertEquals(ArtisticPerformanceSupport.PARTICIPATION, ArtisticPerformanceSupport.result(fields()));
        // the declared result wins over the legacy kind
        assertEquals(ArtisticPerformanceSupport.PARTICIPATION,
                ArtisticPerformanceSupport.result(fields("Rezultat", "Participare", "Tip", "Premiu individual")));
    }

    @Test
    void theStandardCountsTheNamedRolesAndARecordWithoutARole() {
        for (String role : List.of("Compozitor", "Dirijor", "Regizor", "Maestru de balet", "Solist", "Concert-maestru",
                "Membru într-o formație camerală (până la 10 persoane)")) {
            assertTrue(ArtisticPerformanceSupport.roleCounts(fields("Rol", role)), role);
        }
        assertTrue(ArtisticPerformanceSupport.roleCounts(fields()));
        assertFalse(ArtisticPerformanceSupport.roleCounts(fields("Rol", ArtisticPerformanceSupport.ROLE_LARGE_ENSEMBLE_MEMBER)));
        assertFalse(ArtisticPerformanceSupport.roleCounts(fields("Rol", ArtisticPerformanceSupport.ROLE_OTHER)));
    }

    // ── visibility ──

    @Test
    void theRegistryDecidesTheVisibilityOfAListedEvent() {
        var top = ArtisticPerformanceSupport.visibility(Optional.of(ArtisticEvent.Rank.INTERNATIONAL_TOP), fields());
        var international = ArtisticPerformanceSupport.visibility(Optional.of(ArtisticEvent.Rank.INTERNATIONAL), fields());
        var national = ArtisticPerformanceSupport.visibility(Optional.of(ArtisticEvent.Rank.NATIONAL),
                fields("Vizibilitate", "Internațională sau națională de vârf"));
        assertTrue(top.top());
        assertTrue(international.top());
        assertFalse(national.top(), "a national festival is regional/local, whatever is declared");
        assertEquals(ArtisticPerformanceSupport.VisibilityBasis.REGISTRY, national.basis());
    }

    @Test
    void anUnlistedEventTakesTheDeclaredVisibilityElseRegional() {
        var declaredTop = ArtisticPerformanceSupport.visibility(Optional.empty(), fields("Vizibilitate", "Internațională sau națională de vârf"));
        var declaredLocal = ArtisticPerformanceSupport.visibility(Optional.empty(), fields("Vizibilitate", "Regională sau locală"));
        var nothing = ArtisticPerformanceSupport.visibility(Optional.empty(), fields());
        assertTrue(declaredTop.top());
        assertEquals(ArtisticPerformanceSupport.VisibilityBasis.DECLARED, declaredTop.basis());
        assertFalse(declaredLocal.top());
        assertFalse(nothing.top());
        assertEquals(ArtisticPerformanceSupport.VisibilityBasis.DEFAULT, nothing.basis());
    }

    // ── the registry of ranks ──

    @Test
    void theRegistryMatchesNamesWithoutDiacriticsQuotesOrCaseAndKeepsTheBestRank() {
        ArtisticEventRankSupport.register(List.of(
                event("Festivalul „George Enescu” (România)", ArtisticEvent.Rank.INTERNATIONAL_TOP),
                event("Festivalul Internațional de Teatru Sibiu", ArtisticEvent.Rank.INTERNATIONAL),
                event("FESTIVALUL INTERNAȚIONAL DE TEATRU SIBIU", ArtisticEvent.Rank.INTERNATIONAL_TOP)));
        assertEquals(Optional.of(ArtisticEvent.Rank.INTERNATIONAL_TOP), ArtisticEventRankSupport.rankOf("festivalul george enescu romania"));
        assertEquals(Optional.of(ArtisticEvent.Rank.INTERNATIONAL_TOP), ArtisticEventRankSupport.rankOf("Festivalul Internațional de Teatru Sibiu"));
        assertEquals(Optional.empty(), ArtisticEventRankSupport.rankOf("Festivalul George Enescu"), "a different name is a different event");
        assertEquals(Optional.empty(), ArtisticEventRankSupport.rankOf("  "));
        assertEquals(Optional.empty(), ArtisticEventRankSupport.rankOf(null));
    }

    @Test
    void theRegistryLoadsOnFirstUseAndRetriesAfterAFailedLoad() {
        AtomicInteger calls = new AtomicInteger();
        ArtisticEventRankSupport.registerLoader(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("database unreachable");
            }
            return List.of(event("Wien Modern (Austria)", ArtisticEvent.Rank.INTERNATIONAL_TOP));
        });
        assertEquals(Optional.empty(), ArtisticEventRankSupport.rankOf("Wien Modern (Austria)"));
        assertEquals(Optional.of(ArtisticEvent.Rank.INTERNATIONAL_TOP), ArtisticEventRankSupport.rankOf("Wien Modern (Austria)"));
        assertEquals(Optional.of(ArtisticEvent.Rank.INTERNATIONAL_TOP), ArtisticEventRankSupport.rankOf("wien modern austria"));
        assertEquals(2, calls.get(), "loaded once it worked, not again");
    }

    private static ArtisticEvent event(String name, ArtisticEvent.Rank rank) {
        ArtisticEvent e = new ArtisticEvent();
        e.setName(name);
        e.setRank(rank);
        return e;
    }
}
