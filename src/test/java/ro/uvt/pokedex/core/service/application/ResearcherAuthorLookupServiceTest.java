package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.user.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearcherAuthorLookupServiceTest {

    private final ResearcherAuthorLookupService service = new ResearcherAuthorLookupService();

    @Test
    void resolveAuthorLookupKeysReturnsEmptyWhenProfileIsNull() {
        assertTrue(service.resolveAuthorLookupKeys(null).isEmpty());
    }

    @Test
    void resolveAuthorLookupKeysBuildsNormalizedOrderedDistinctKeys() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setPrimaryScholardexAuthorId("  author-1 ");
        profile.setScopusId(List.of("  sc-1 ", "sc-2", "author-1", " "));
        profile.setWosId(List.of(" wos-1 ", "sc-2", "", "wos-2"));
        profile.setScholarId("  scholar-1 ");

        List<String> keys = service.resolveAuthorLookupKeys(profile);

        assertEquals(List.of("author-1", "sc-1", "sc-2", "wos-1", "wos-2", "scholar-1"), keys);
    }

    @Test
    void resolveAuthorLookupKeysSkipsNullCollectionsAndBlankValues() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setPrimaryScholardexAuthorId(" ");
        profile.setScopusId(null);
        profile.setWosId(null);
        profile.setScholarId(null);

        List<String> keys = service.resolveAuthorLookupKeys(profile);
        assertTrue(keys.isEmpty());
    }
}

