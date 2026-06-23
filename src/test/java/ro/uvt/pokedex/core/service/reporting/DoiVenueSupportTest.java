package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoiVenueSupportTest {

    @Test
    void recognizesSpringerIsbnDoiWithAndWithoutUrlPrefix() {
        assertTrue(DoiVenueSupport.isSpringerBookSeriesProceedings(withDoi("10.1007/978-3-319-43177-2_6")));
        assertTrue(DoiVenueSupport.isSpringerBookSeriesProceedings(withDoi("https://doi.org/10.1007/978-3-030-12345-6_1")));
        assertTrue(DoiVenueSupport.isSpringerBookSeriesProceedings(withDoi("HTTPS://DOI.ORG/10.1007/978-1-4471-1234-5")));
    }

    @Test
    void doesNotMatchSpringerJournalOrOtherPrefixes() {
        assertFalse(DoiVenueSupport.isSpringerBookSeriesProceedings(withDoi("10.1007/s11227-020-03253-7"))); // Springer journal
        assertFalse(DoiVenueSupport.isSpringerBookSeriesProceedings(withDoi("10.1109/TPDS.2019.1234567"))); // IEEE (journal or conf)
        assertFalse(DoiVenueSupport.isSpringerBookSeriesProceedings(withDoi("10.1145/3293883.3295701")));
        assertFalse(DoiVenueSupport.isSpringerBookSeriesProceedings(withDoi(null)));
    }

    private ScoringPublication withDoi(String doi) {
        return new ScoringPublication("p1", null, "forum-1", "2020-01-01", "ar", "ar",
                List.of("a1"), 1, doi, null, "Some Title", 0, Set.of());
    }
}
