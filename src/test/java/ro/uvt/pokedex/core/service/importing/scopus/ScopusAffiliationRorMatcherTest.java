package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScopusAffiliationRorMatcherTest {

    private ScholardexAffiliationFact backboneUvt() {
        ScholardexAffiliationFact f = new ScholardexAffiliationFact();
        f.setId("saff_uvt");
        f.setName("West University of Timişoara");
        f.setAliases(new java.util.ArrayList<>(List.of("Universitatea de Vest din Timișoara", "UVT")));
        f.setCity("Timișoara");
        f.setCountry("Romania");
        return f;
    }

    private ScopusAffiliationRorMatcher matcher() {
        return ScopusAffiliationRorMatcher.build(List.of(backboneUvt()));
    }

    @Test
    void tier1ExactAliasMatchesCrossLanguageName() {
        // The Romanian display name is on the ROR record's aliases — the cross-language linchpin.
        assertEquals("saff_uvt",
                matcher().match("Universitatea de Vest din Timișoara", "Timișoara", "Romania").getId());
        // The English display name (the record's `name`).
        assertEquals("saff_uvt",
                matcher().match("West University of Timisoara", "Timisoara", "Romania").getId());
        // Acronym alias.
        assertEquals("saff_uvt", matcher().match("UVT", null, "Romania").getId());
    }

    @Test
    void tier2SimplificationStripsFacultyPrefixAndParenthetical() {
        assertEquals("saff_uvt",
                matcher().match("Faculty of Mathematics, West University of Timisoara", "Timisoara", "Romania").getId());
        assertEquals("saff_uvt",
                matcher().match("West University of Timisoara (UVT)", "Timisoara", "Romania").getId());
    }

    @Test
    void tier3CountryGatedJaccardMatchesReorderedTokens() {
        // Reordered tokens — not an exact alias, but token-Jaccard 1.0 within the same country.
        assertEquals("saff_uvt",
                matcher().match("University of West Timisoara", "Timisoara", "Romania").getId());
    }

    @Test
    void countryGateBlocksCrossCountryJaccard() {
        // Same fuzzy name but wrong country → no candidates in that bucket → no match.
        assertNull(matcher().match("University of West Timisoara", "Timisoara", "France"));
    }

    @Test
    void unrelatedAffiliationDoesNotMatch() {
        assertNull(matcher().match("Harvard University", "Cambridge", "United States"));
    }

    @Test
    void blankNameReturnsNull() {
        assertNull(matcher().match("  ", "Timisoara", "Romania"));
    }
}
