package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.repository.reporting.PsihologiePublisherRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H143 — the category of a declared book's publisher, from the lists each standard names. */
class PublisherCategoryServiceTest {

    /** Stands in for the Mongo-backed WoS Master Book List; Excelsior Art is one of its four Romanian houses. */
    private static final Set<String> MASTER_BOOK_LIST = Set.of("routledge", "springer", "excelsior art");

    private final PublisherCategoryService service = service();

    @BeforeEach
    void registerTheInternationalLists() {
        InternationalPublisherSupport.register(
                new InternationalPublisherListService(InternationalPublisherListServiceTest.senseRankings()));
    }

    @AfterEach
    void resetTheInternationalLists() {
        InternationalPublisherSupport.reset();
    }

    private static PublisherCategoryService service() {
        WosMasterBookListService masterBookList = mock(WosMasterBookListService.class);
        when(masterBookList.isRecognized(any())).thenAnswer(call -> {
            String name = call.getArgument(0);
            return name != null && MASTER_BOOK_LIST.contains(name.trim().toLowerCase(java.util.Locale.ROOT));
        });
        return new PublisherCategoryService(new PsihologiePublisherService(mock(PsihologiePublisherRepository.class)),
                masterBookList);
    }

    private String category(PublisherRules rules, String publisher) {
        return service.classify(rules, publisher).map(PublisherCategorySupport.Classification::category).orElse(null);
    }

    // ── matching names ─────────────────────────────────────────────────────────

    @Test
    void namesAreComparedByTheirDistinctiveWords() {
        assertEquals(List.of("universitatii", "vest", "timisoara"),
                PublisherNameMatcher.words("Editura Universității de Vest din Timișoara"));
        assertEquals(List.of("muzicala"), PublisherNameMatcher.words("SC EDITURA MUZICALA SRL"));
        assertEquals(3, PublisherNameMatcher.match(PublisherNameMatcher.words("Editura Polirom"),
                PublisherNameMatcher.words("POLIROM")));
        assertEquals(2, PublisherNameMatcher.match(PublisherNameMatcher.words("Editura Polirom"),
                PublisherNameMatcher.words("Editura Polirom, Iași")), "the listed name inside the typed one");
        assertEquals(1, PublisherNameMatcher.match(PublisherNameMatcher.words("Editura Universității de Vest din Timișoara"),
                PublisherNameMatcher.words("Editura Universității de Vest")), "a typed name of two words inside the listed one");
        assertEquals(0, PublisherNameMatcher.match(PublisherNameMatcher.words("Editura Universității de Vest din Timișoara"),
                PublisherNameMatcher.words("Editura Universității")), "one word inside a longer name is no match");
    }

    // ── Comisia 35, Muzică ─────────────────────────────────────────────────────

    @Test
    void musicTakesTheCncsCategoryOfTheMusicDomainFirst() {
        assertEquals("A", category(PublisherRules.MUZICA_2026, "Editura MediaMusica"));
        assertEquals("A", category(PublisherRules.MUZICA_2026, "Editura UNMB"), "an alias");
        assertEquals("B", category(PublisherRules.MUZICA_2026, "Editura Eikon, Cluj-Napoca"));
        assertEquals("C", category(PublisherRules.MUZICA_2026, "Universitaria"),
                "rated C in Music twice: the B of 2013 for the performing arts does not count over it");
        PublisherCategorySupport.Classification unmb = service.classify(PublisherRules.MUZICA_2026,
                "Editura Universității Naționale de Muzică București").orElseThrow();
        assertEquals("CNCS", unmb.basis());
        assertTrue(unmb.detail().startsWith("CNCS 2026, Muzică"), unmb.detail());
    }

    @Test
    void aPublisherTheMusicListsDoNotRateTakesItsBestCategoryElsewhere() {
        PublisherCategorySupport.Classification uvt = service.classify(PublisherRules.MUZICA_2026,
                "Editura Universității de Vest").orElseThrow();
        assertEquals("A", uvt.category(), "A in Filologie 2026");
        assertEquals("A", category(PublisherRules.MUZICA_2026, "Editura UVT"), "an alias");
    }

    @Test
    void anEquivalentForeignPublisherIsOnTheMasterBookListOrAnInternationalList() {
        assertEquals("STRAINA", category(PublisherRules.MUZICA_2026, "Bärenreiter-Verlag Kassel"));
        assertEquals("STRAINA", category(PublisherRules.MUZICA_2026, "Ricordi"), "an alias of Casa Ricordi");
        assertEquals("STRAINA", category(PublisherRules.MUZICA_2026, "Routledge"));
        PublisherCategorySupport.Classification brill = service.classify(PublisherRules.MUZICA_2026, "Brill").orElseThrow();
        assertEquals("STRAINA", brill.category());
        assertEquals("INTERNATIONAL_LIST", brill.basis());
        assertTrue(brill.detail().startsWith("Clasamentul SENSE"), brill.detail());
        assertNull(category(PublisherRules.MUZICA_2026, "Lambert Academic Publishing"), "UEFISCDI excludes it");
        assertEquals(Optional.empty(), service.classify(PublisherRules.MUZICA_2026, "Editura Proprie"));
    }

    @Test
    void aRomanianHouseIsNeverAForeignOne() {
        assertEquals(Optional.empty(), service.classify(PublisherRules.MUZICA_2026, "Excelsior Art"),
                "on the WoS Master Book List, but Romanian");
        assertEquals(Optional.empty(), service.classify(PublisherRules.MUZICA_2026, "Editura Peter Lang"),
                "a foreign name is written without «Editura»");
        assertEquals("STRAINA", category(PublisherRules.MUZICA_2026, "Peter Lang"));
    }

    // ── Comisia 25 and 28 ──────────────────────────────────────────────────────

    @Test
    void sociologyAndComisia28UseTheirOwnListsAndTheMasterBookListForA1() {
        assertEquals("A2", category(PublisherRules.SOCIOLOGIE_2026, "Editura Polirom"));
        assertEquals("A1", category(PublisherRules.SOCIOLOGIE_2026, "Routledge"));
        assertNull(category(PublisherRules.SOCIOLOGIE_2026, "Editura Proprie"));
        assertEquals("A2", category(PublisherRules.PSIHOLOGIE_2026, "Polirom"));
        assertEquals("B", category(PublisherRules.PSIHOLOGIE_2026, "Humanitas"));
        assertEquals("A2", category(PublisherRules.STIINTE_EDUCATIEI_2026, "Editura Didactica si Pedagogica"));
        assertEquals("A1", category(PublisherRules.STIINTE_EDUCATIEI_2026, "Springer"));
        assertEquals("WOS_MASTER_BOOK_LIST",
                service.classify(PublisherRules.PSIHOLOGIE_2026, "Routledge").orElseThrow().basis());
        assertEquals("A1", category(PublisherRules.SOCIOLOGIE_2026, "Excelsior Art"),
                "the Master Book List counts as for corpus books: international prestige, not a foreign house");
    }

    @Test
    void anInternationalListOrRankingMakesAHouseA1BeforeAnyHeadIsAsked() {
        PublisherCategorySupport.Classification harmattan =
                service.classify(PublisherRules.SOCIOLOGIE_2026, "L'Harmattan, Paris").orElseThrow();
        assertEquals("A1", harmattan.category());
        assertEquals("INTERNATIONAL_LIST", harmattan.basis());
        assertTrue(harmattan.detail().startsWith("UEFISCDI, edituri pentru științele sociale"), harmattan.detail());
        assertEquals("A1", category(PublisherRules.PSIHOLOGIE_2026, "Polity Press"), "SENSE B");
        assertEquals("A1", category(PublisherRules.STIINTE_EDUCATIEI_2026, "Brill"), "SENSE B");
        assertNull(category(PublisherRules.PSIHOLOGIE_2026, "Acco"), "SENSE C does not count");
        assertEquals("A2", category(PublisherRules.SOCIOLOGIE_2026, "Editura Economica"), "on the Sociology list");
        assertNull(category(PublisherRules.PSIHOLOGIE_2026, "Economica"),
                "Editura Economică is not on the Psychology list, and it is not the French Economica of Anexa 7c");
    }

    // ── the outcome a formula reads ────────────────────────────────────────────

    private static PublisherClaim claim(PublisherClaim.Status status, String requested) {
        PublisherClaim claim = new PublisherClaim();
        claim.setStatus(status);
        claim.setRequested(requested);
        return claim;
    }

    @Test
    void anApprovedRequestCountsOnlyWhileTheRecordStillAsksForIt() {
        PublisherCategorySupport.register(service);
        try {
            String worldCat = "A1 — minimum 25 de biblioteci universitare din UE/OCDE în WorldCat";
            Map<String, String> asks = Map.of(PublisherRules.FIELD_CLAIM, worldCat);
            PublisherCategorySupport.Outcome approved = PublisherCategorySupport.outcome(PublisherRules.PSIHOLOGIE_2026,
                    "Editura Proprie", claim(PublisherClaim.Status.APPROVED, worldCat), asks);
            assertEquals("A1", approved.category());
            assertEquals("APPROVED_CLAIM", approved.basis());

            assertNull(PublisherCategorySupport.outcome(PublisherRules.PSIHOLOGIE_2026, "Editura Proprie",
                    claim(PublisherClaim.Status.PENDING, worldCat), asks).category());
            assertNull(PublisherCategorySupport.outcome(PublisherRules.PSIHOLOGIE_2026, "Editura Proprie",
                    claim(PublisherClaim.Status.APPROVED, worldCat),
                    Map.of(PublisherRules.FIELD_CLAIM, "B — un criteriu din ruta complementară")).category(),
                    "the record asks for something else now: back to a head");
            assertNull(PublisherCategorySupport.outcome(PublisherRules.SOCIOLOGIE_2026, "Editura Proprie",
                    claim(PublisherClaim.Status.APPROVED, worldCat), asks).category(),
                    "an option of another standard grants nothing");
        } finally {
            PublisherCategorySupport.reset();
        }
    }

    @Test
    void theBetterOfTheListAndTheRequestCountsAndTheOutcomeSaysWhy() {
        PublisherCategorySupport.register(service);
        try {
            String twoCriteria = "A2 — cel puțin două criterii din ruta complementară";
            Map<String, String> asks = Map.of(PublisherRules.FIELD_CLAIM, twoCriteria);
            assertEquals("A2", PublisherCategorySupport.outcome(PublisherRules.PSIHOLOGIE_2026, "Humanitas",
                    claim(PublisherClaim.Status.APPROVED, twoCriteria), asks).category(), "B on the list, A2 by the route");
            assertEquals("LIST", PublisherCategorySupport.outcome(PublisherRules.PSIHOLOGIE_2026, "Polirom",
                    claim(PublisherClaim.Status.APPROVED, "B — un criteriu din ruta complementară"),
                    Map.of(PublisherRules.FIELD_CLAIM, "B — un criteriu din ruta complementară")).basis());

            PublisherCategorySupport.Outcome cncsC = PublisherCategorySupport.outcome(PublisherRules.MUZICA_2026,
                    "Universitaria", null, Map.of());
            assertNull(cncsC.category());
            assertEquals("LISTED_NOT_COUNTED", cncsC.basis());
            assertEquals("C", cncsC.listed().category());
            assertEquals("NO_PUBLISHER", PublisherCategorySupport.outcome(PublisherRules.MUZICA_2026, " ", null, Map.of()).basis());
            assertEquals("NOT_LISTED", PublisherCategorySupport.outcome(PublisherRules.MUZICA_2026, "Editura Proprie", null, Map.of()).basis());
        } finally {
            PublisherCategorySupport.reset();
        }
    }

    @Test
    void aListedPublisherNamedInAnImportedLineIsFoundThere() {
        assertEquals(Optional.of("Editura Universității Naționale de Muzică București"), service.findIn(
                "Studii de stilistică, București: Editura Universității Naționale de Muzică București, 2019, ISBN 978"));
        assertEquals(Optional.of("Cambridge University Press"),
                service.findIn("A chapter, in: The Cambridge Companion, Cambridge University Press, 2021"));
        assertEquals(Optional.of("EDWARD ELGAR"),
                service.findIn("Handbook of Social Policy, Cheltenham: Edward Elgar, 2020"), "Anexa 7c");
        assertEquals(Optional.empty(), service.findIn("Concert de Crăciun, Universitaria, Craiova"),
                "a one-word name is too common in a free text");
        assertEquals(Optional.empty(), service.findIn("Recital, sala Capitol, Timișoara"));
    }

    @Test
    void everyRequestOptionAsksForACategoryOfSomeStandard() {
        for (Map.Entry<String, String> option : PublisherRules.CLAIM_OPTIONS.entrySet()) {
            boolean somewhere = false;
            for (PublisherRules rules : PublisherRules.values()) {
                somewhere |= rules.claimCategory(option.getKey()).isPresent();
            }
            assertTrue(somewhere, option.getKey());
        }
    }
}
