package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Domain;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoringCategorySupportTest {

    @Test
    void normalizeCategoryHandlesNullAndWhitespace() {
        assertEquals("", ScoringCategorySupport.normalizeCategory(null));
        assertEquals("", ScoringCategorySupport.normalizeCategory("   "));
        assertEquals("ABC", ScoringCategorySupport.normalizeCategory("  ABC "));
    }

    @Test
    void extractCategoryPartsHandleDelimiterAndMissingDelimiter() {
        assertEquals("COMPUTER SCIENCE", ScoringCategorySupport.extractCategoryName("COMPUTER SCIENCE-SCIE"));
        assertEquals("SCIE", ScoringCategorySupport.extractCategoryIndex("COMPUTER SCIENCE-SCIE"));
        assertEquals("COMPUTER SCIENCE", ScoringCategorySupport.extractCategoryName("COMPUTER SCIENCE"));
        assertEquals("", ScoringCategorySupport.extractCategoryIndex("COMPUTER SCIENCE"));
    }

    @Test
    void isScieOrSsciIndexRecognizesExpectedIndices() {
        assertTrue(ScoringCategorySupport.isScieOrSsciIndex("SCIE"));
        assertTrue(ScoringCategorySupport.isScieOrSsciIndex("X-SSCI"));
        assertFalse(ScoringCategorySupport.isScieOrSsciIndex("ESCI"));
        assertFalse(ScoringCategorySupport.isScieOrSsciIndex(""));
    }

    @Test
    void domainEligibilityForAllAndSpecificDomainBehavesSafely() {
        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        Domain specificDomain = new Domain();
        specificDomain.setName("SPECIFIC");
        specificDomain.setWosCategories(List.of("COMPUTER SCIENCE-SCIE"));

        assertTrue(ScoringCategorySupport.isCategoryEligibleForDomain(allDomain, "COMPUTER SCIENCE-SCIE"));
        assertFalse(ScoringCategorySupport.isCategoryEligibleForDomain(allDomain, "COMPUTER SCIENCE"));

        assertTrue(ScoringCategorySupport.isCategoryEligibleForDomain(specificDomain, "COMPUTER SCIENCE-SCIE"));
        assertFalse(ScoringCategorySupport.isCategoryEligibleForDomain(specificDomain, "OTHER-SCIE"));
        assertFalse(ScoringCategorySupport.isCategoryEligibleForDomain(specificDomain, null));
        assertFalse(ScoringCategorySupport.isCategoryEligibleForDomain(specificDomain, " "));
    }
}
