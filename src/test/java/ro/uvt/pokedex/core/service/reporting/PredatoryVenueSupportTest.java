package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredatoryVenueSupportTest {

    private static ScholardexForumView forum(String name, String publisher) {
        ScholardexForumView f = new ScholardexForumView();
        f.setPublicationName(name);
        f.setPublisher(publisher);
        return f;
    }

    @Test
    void excludesStandardNamedFamiliesByNameOrPublisher() {
        assertTrue(PredatoryVenueSupport.isExcludedVenue(forum("WSEAS Transactions on Signal Processing", null)));
        assertTrue(PredatoryVenueSupport.isExcludedVenue(forum("Proceedings of the 9th WSEAS/IASME International Conference", null)));
        assertTrue(PredatoryVenueSupport.isExcludedVenue(forum("IAENG International Journal of Computer Science", null)));
        assertTrue(PredatoryVenueSupport.isExcludedVenue(forum("Proceedings of the DAAAM International Symposium", null)));
        assertTrue(PredatoryVenueSupport.isExcludedVenue(forum("Some Conference", "WSEAS Press")));
    }

    @Test
    void doesNotExcludeLegitimateVenues() {
        assertFalse(PredatoryVenueSupport.isExcludedVenue(forum("IEEE Transactions on Signal Processing", "IEEE")));
        assertFalse(PredatoryVenueSupport.isExcludedVenue(forum("Lecture Notes in Computer Science", "Springer")));
        assertFalse(PredatoryVenueSupport.isExcludedVenue(forum(null, null)));
        assertFalse(PredatoryVenueSupport.isExcludedVenue(null));
    }
}
