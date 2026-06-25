package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // Record carries a DOI that resolves (via the publication) to forum f-doi, AND an ISSN that would match a
        // different forum — DOI must win.
        ScholardexForumFact byDoi = forum("f-doi", null, null, "Some Conf", false);
        ScholardexForumFact byIssn = forum("f-issn", "1111-2222", null, "Other Conf", false);
        WosCpciRecord r = rec("10.1/abc", "1111-2222", null, null, null);

        WosCpciMatchReport report = WosCpciOnboardingService.match(
                List.of(r), List.of(byDoi, byIssn), Map.of("10.1/abc", "f-doi"));

        assertEquals(1, report.totalRecords());
        assertEquals(1, report.matchedByDoi());
        assertEquals(0, report.matchedByIssnIsbn());
        assertEquals(1, report.distinctForumsMatched());
        assertEquals(1, report.forumsNetNew());
        assertTrue(report.netNewForumIdsSample().contains("f-doi"));
    }

    @Test
    void issnThenIsbnThenTitleFallThrough() {
        ScholardexForumFact byIssn = forum("f-issn", "2194-5357", null, null, false);
        ScholardexForumFact byIsbn = forum("f-isbn", null, "978-3-642-33017-9", null, false);
        ScholardexForumFact byName = forum("f-name", null, null, "Physics Conference", false);

        // ISSN hit (hyphen-insensitive); ISBN hit; title hit via normalizeVenueName (drops the ordinal + year + parens).
        WosCpciRecord issnRec = rec(null, "21945357", null, null, null);
        WosCpciRecord isbnRec = rec(null, null, "9783642330179", null, null);
        WosCpciRecord titleRec = rec(null, null, null, "1st Physics Conference (2009)", null);
        WosCpciRecord miss = rec(null, "0000-0000", null, "Totally Unknown Symposium", null);

        WosCpciMatchReport report = WosCpciOnboardingService.match(
                List.of(issnRec, isbnRec, titleRec, miss), List.of(byIssn, byIsbn, byName), Map.of());

        assertEquals(4, report.totalRecords());
        assertEquals(0, report.matchedByDoi());
        assertEquals(2, report.matchedByIssnIsbn());
        assertEquals(1, report.matchedByTitle());
        assertEquals(1, report.unmatched());
        assertEquals(3, report.distinctForumsMatched());
        assertEquals("Totally Unknown Symposium", report.topUnmatchedVenues().getFirst().title());
    }

    @Test
    void alreadyWosForumsAreNotCountedAsNetNew() {
        ScholardexForumFact already = forum("f-old", "1111-2222", null, null, true);
        WosCpciRecord r = rec(null, "1111-2222", null, null, null);

        WosCpciMatchReport report = WosCpciOnboardingService.match(List.of(r), List.of(already), Map.of());

        assertEquals(1, report.distinctForumsMatched());
        assertEquals(1, report.forumsAlreadyWos());
        assertEquals(0, report.forumsNetNew());
    }

    @Test
    void normalizeIsbnStripsSeparatorsAndKeepsX() {
        assertEquals("978007354081X", WosCpciOnboardingService.normalizeIsbn("978-0-0735-4081-x"));
        assertNull(WosCpciOnboardingService.normalizeIsbn("  "));
        assertNull(WosCpciOnboardingService.normalizeIsbn(null));
    }
}
