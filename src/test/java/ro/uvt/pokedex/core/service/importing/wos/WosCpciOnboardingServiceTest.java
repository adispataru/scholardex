package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WosCpciOnboardingServiceTest {

    private static ScholardexForumFact forum(String id, String issn, String isbn, String name, boolean wos) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setId(id);
        f.setIssn(issn);
        f.setIsbn(isbn);
        f.setName(name);
        if (wos) {
            f.setWosForumIds(List.of("wos-" + id));
        }
        return f;
    }

    private static WosCpciRecord rec(String doi, String issn, String isbn, String confTitle, String sourceTitle) {
        return new WosCpciRecord("WOS:x", doi, sourceTitle, confTitle, null, issn, null, isbn, "2020");
    }

    @Test
    void doiMatchWinsAndIsCountedFirst() {
        ScholardexForumFact byDoi = forum("f-doi", null, null, "Some Conf", false);
        ScholardexForumFact byIssn = forum("f-issn", "1111-2222", null, "Other Conf", false);
        WosCpciRecord r = rec("10.1/abc", "1111-2222", null, null, null);

        WosCpciOnboardingService.MatchResult res = WosCpciOnboardingService.matchAll(
                List.of(r), List.of(byDoi, byIssn), Map.of("10.1/abc", "f-doi"));

        assertEquals(1, res.matchedByDoi());
        assertEquals(0, res.matchedByIssnIsbn());
        assertEquals(1, res.matchedForumIds().size());
        assertTrue(res.netNewForumIds().contains("f-doi"));
    }

    @Test
    void issnThenIsbnThenExactTitleFallThrough() {
        ScholardexForumFact byIssn = forum("f-issn", "2194-5357", null, null, false);
        ScholardexForumFact byIsbn = forum("f-isbn", null, "978-3-642-33017-9", null, false);
        ScholardexForumFact byName = forum("f-name", null, null, "Physics Conference", false);

        WosCpciRecord issnRec = rec(null, "21945357", null, null, null);
        WosCpciRecord isbnRec = rec(null, null, "9783642330179", null, null);
        WosCpciRecord titleRec = rec(null, null, null, "1st Physics Conference (2009)", null);
        WosCpciRecord miss = rec(null, "0000-0000", null, "Totally Unknown Symposium", null);

        WosCpciOnboardingService.MatchResult res = WosCpciOnboardingService.matchAll(
                List.of(issnRec, isbnRec, titleRec, miss), List.of(byIssn, byIsbn, byName), Map.of());

        assertEquals(2, res.matchedByIssnIsbn());
        assertEquals(1, res.matchedByTitle());
        assertEquals(0, res.matchedByTitleContains());
        assertEquals(3, res.matchedForumIds().size());
        assertEquals(1, res.unmatchedVenues().get("Totally Unknown Symposium"));
    }

    @Test
    void titleContainmentRecoversPerEditionProceedingsForum() {
        // The SYNASC case: forum carries "Proceedings - 9th ... SYNASC 2007"; the WoS title core appears verbatim
        // inside it (after ordinal/year normalization) but is not an exact match. A short generic title must NOT match.
        ScholardexForumFact synasc = forum("f-synasc", null, null,
                "Proceedings - 9th International Symposium on Symbolic and Numeric Algorithms for Scientific Computing, SYNASC 2007",
                false);

        WosCpciRecord hit = rec(null, null, null,
                "18th International Symposium on Symbolic and Numeric Algorithms for Scientific Computing", null);
        // Short title (< MIN_CONTAINMENT_LEN) that matches no forum exactly → must stay unmatched (no loose containment).
        WosCpciRecord shortMiss = rec(null, null, null, "Tiny Symposium", null);

        WosCpciOnboardingService.MatchResult res = WosCpciOnboardingService.matchAll(
                List.of(hit, shortMiss), List.of(synasc), Map.of());

        assertEquals(1, res.matchedByTitleContains());
        assertTrue(res.netNewForumIds().contains("f-synasc"));
        assertEquals(1, res.matchedForumIds().size(), "the short title must not match anything");
    }

    @Test
    void alreadyWosOrAlreadyCpciAreNotNetNew() {
        ScholardexForumFact already = forum("f-old", "1111-2222", null, null, true);
        ScholardexForumFact cpciTagged = forum("f-cpci", "3333-4444", null, null, false);
        cpciTagged.setWosCpciIndexed(true); // a prior CPCI apply — idempotent, not net-new again

        WosCpciOnboardingService.MatchResult res = WosCpciOnboardingService.matchAll(
                List.of(rec(null, "1111-2222", null, null, null), rec(null, "3333-4444", null, null, null)),
                List.of(already, cpciTagged), Map.of());

        assertEquals(2, res.matchedForumIds().size());
        assertTrue(res.netNewForumIds().isEmpty(), "both forums are already WoS/CPCI → no net-new");
    }

    @Test
    void normalizeIsbnStripsSeparatorsAndKeepsX() {
        assertEquals("978007354081X", WosCpciOnboardingService.normalizeIsbn("978-0-0735-4081-x"));
        assertNull(WosCpciOnboardingService.normalizeIsbn("  "));
        assertNull(WosCpciOnboardingService.normalizeIsbn(null));
    }
}
