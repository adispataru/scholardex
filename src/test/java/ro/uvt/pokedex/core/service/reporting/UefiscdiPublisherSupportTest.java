package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H136 — the Anexa 7c publisher list: bundled, exact-name matched, with the list's abbreviations folded. */
class UefiscdiPublisherSupportTest {

    @AfterEach
    void unregister() {
        UefiscdiPublisherSupport.register(List.of());
    }

    @Test
    void theBundledListHasThe253PublishersOfAnexa7c() {
        List<String> names = UefiscdiPublisherListService.readFixture();
        assertEquals(253, names.size());
        assertEquals("ACADEMIC PRESS", names.getFirst());
        assertEquals("ZED BOOKS", names.getLast());
    }

    @Test
    void whatAResearcherTypesMeetsTheListsAbbreviations() {
        UefiscdiPublisherSupport.register(UefiscdiPublisherListService.readFixture());
        assertTrue(UefiscdiPublisherSupport.isOnAnexa7c("Cambridge University Press"), "CAMBRIDGE UNIV. PRESS");
        assertTrue(UefiscdiPublisherSupport.isOnAnexa7c("cambridge univ. press"));
        assertTrue(UefiscdiPublisherSupport.isOnAnexa7c("Harper and Row"), "HARPER & ROW");
        assertTrue(UefiscdiPublisherSupport.isOnAnexa7c("Simon & Schuster"));
        assertTrue(UefiscdiPublisherSupport.isOnAnexa7c("Addison Wesley"), "ADDISON-WESLEY");
        assertTrue(UefiscdiPublisherSupport.isOnAnexa7c("  Routledge "));
    }

    @Test
    void aNameOffTheListOrAFragmentOfOneDoesNotMatch() {
        UefiscdiPublisherSupport.register(UefiscdiPublisherListService.readFixture());
        assertFalse(UefiscdiPublisherSupport.isOnAnexa7c("Press"), "a fragment must not match by substring");
        assertFalse(UefiscdiPublisherSupport.isOnAnexa7c("Editura Universității de Vest"));
        assertFalse(UefiscdiPublisherSupport.isOnAnexa7c(""));
        assertFalse(UefiscdiPublisherSupport.isOnAnexa7c(null));
    }

    @Test
    void nothingMatchesBeforeTheListIsLoaded() {
        assertFalse(UefiscdiPublisherSupport.isOnAnexa7c("Routledge"));
    }
}
