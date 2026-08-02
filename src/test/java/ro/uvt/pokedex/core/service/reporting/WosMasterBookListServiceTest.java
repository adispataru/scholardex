package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H98: the Master Book List is written in Web of Science's abbreviated shouting style, while the
 * publisher strings we hold come from Scopus/OpenAlex in ordinary prose. These are the real pairs
 * observed in {@code scholardex.book_facts} against the real archived list entries — exact matching
 * scored 0 on all of them, which is what motivated canonical token matching.
 */
class WosMasterBookListServiceTest {

    private static boolean matches(String wosName, String scopusName) {
        Set<String> listed = WosMasterBookListService.canonicalTokens(wosName);
        Set<String> candidate = WosMasterBookListService.canonicalTokens(scopusName);
        if (candidate.equals(listed)) {
            return true;
        }
        Set<String> smaller = candidate.size() <= listed.size() ? candidate : listed;
        Set<String> larger = smaller == candidate ? listed : candidate;
        return larger.containsAll(smaller)
                && smaller.stream().anyMatch(t -> !GENERIC_FOR_TEST.contains(t));
    }

    // Mirrors the service's GENERIC set for the assertions below.
    private static final Set<String> GENERIC_FOR_TEST = Set.of(
            "press", "publishing", "publishers", "publication", "publications", "books", "book",
            "editions", "verlag", "university", "international", "national", "house", "media",
            "imprint", "science", "sciences", "scientific", "academic", "academy", "society",
            "association", "college", "institute", "school", "studies");

    @Test
    void abbreviationsAndLegalFormsStillMeetTheirScopusSpelling() {
        assertTrue(matches("OXFORD UNIV PRESS", "Oxford University Press"));       // UNIV -> university
        assertTrue(matches("CAMBRIDGE UNIV PRESS", "Cambridge University Press"));
        assertTrue(matches("TAYLOR & FRANCIS LTD", "Taylor and Francis"));         // & / and / LTD dropped
        assertTrue(matches("JOHN WILEY & SONS LTD", "wiley"));                     // subset, identifying token
        assertTrue(matches("SPRINGER", "Springer International Publishing"));      // subset the other way
        assertTrue(matches("WORLD SCIENTIFIC PUBL CO PTE LTD", "World Scientific"));
        assertTrue(matches("ACADEMIC PRESS", "Academic Press"));                   // all-generic but EQUAL
        assertTrue(matches("ELSEVIER", "Elsevier"));
        assertTrue(matches("CRC PRESS-TAYLOR & FRANCIS GROUP", "CRC Press"));
    }

    @Test
    void genericFragmentsDoNotMatchTheWholeList() {
        // A publisher string that carries no identifying token must not subset-match everything.
        assertFalse(matches("OXFORD UNIV PRESS", "Press"));
        assertFalse(matches("SPRINGER", "Publishing House"));
        assertFalse(matches("CAMBRIDGE UNIV PRESS", "University"));
        // Different houses that merely share a generic word stay apart.
        assertFalse(matches("PRINCETON UNIV PRESS", "Oxford University Press"));
        assertFalse(matches("ROUTLEDGE", "Bloomsbury Publishing Plc."));
    }

    @Test
    void canonicalTokensExpandAbbreviationsAndDropNoise() {
        assertEquals(Set.of("oxford", "university", "press"),
                WosMasterBookListService.canonicalTokens("OXFORD UNIV PRESS"));
        assertEquals(Set.of("taylor", "francis"),
                WosMasterBookListService.canonicalTokens("TAYLOR & FRANCIS LTD"));
        assertEquals(Set.of("world", "scientific", "publishing"),
                WosMasterBookListService.canonicalTokens("WORLD SCIENTIFIC PUBL CO PTE LTD"));
        assertTrue(WosMasterBookListService.canonicalTokens("   ").isEmpty());
    }
}
