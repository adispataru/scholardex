package ro.uvt.pokedex.core.service.importing.wos;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WosCanonicalContractSupportTest {

    @Test
    void scienceTokenNormalizesToScie() {
        Set<EditionNormalized> editions = WosCanonicalContractSupport.normalizeEditionCandidates("SCIENCE");
        assertEquals(Set.of(EditionNormalized.SCIE), editions);
    }

    @Test
    void socialSciencesTokenNormalizesToSsci() {
        assertEquals(Set.of(EditionNormalized.SSCI), WosCanonicalContractSupport.normalizeEditionCandidates("social sciences"));
        assertEquals(Set.of(EditionNormalized.SSCI), WosCanonicalContractSupport.normalizeEditionCandidates("SOCIAL SCIENCE"));
    }

    @Test
    void mixedScienceAndSocialSciencesNormalizeToScieAndSsci() {
        Set<EditionNormalized> editions = WosCanonicalContractSupport.normalizeEditionCandidates("science + social sciences");
        assertEquals(Set.of(EditionNormalized.SSCI, EditionNormalized.SCIE), editions);
    }

    @Test
    void bundledScieAndSsciRequiresSplit() {
        String raw = "COMPUTER SCIENCE - SCIE; BUSINESS - SSCI";
        Set<EditionNormalized> editions = WosCanonicalContractSupport.normalizeEditionCandidates(raw);
        assertEquals(Set.of(EditionNormalized.SCIE, EditionNormalized.SSCI), editions);
        assertTrue(WosCanonicalContractSupport.requiresSplitByEdition(raw));
    }

    @Test
    void blankEditionNormalizesToUnknown() {
        assertEquals(Set.of(EditionNormalized.UNKNOWN), WosCanonicalContractSupport.normalizeEditionCandidates(" "));
    }

    @Test
    void unknownEditionNormalizesToOther() {
        assertEquals(Set.of(EditionNormalized.OTHER), WosCanonicalContractSupport.normalizeEditionCandidates("BKCI"));
    }

    @Test
    void ahciAndEsciNormalizeExplicitly() {
        assertEquals(Set.of(EditionNormalized.AHCI), WosCanonicalContractSupport.normalizeEditionCandidates("AHCI"));
        assertEquals(Set.of(EditionNormalized.ESCI), WosCanonicalContractSupport.normalizeEditionCandidates("ESCI"));
    }

    @Test
    void bundledExtendedEditionsRequireSplit() {
        String raw = "SCIE + AHCI";
        assertEquals(Set.of(EditionNormalized.SCIE, EditionNormalized.AHCI), WosCanonicalContractSupport.normalizeEditionCandidates(raw));
        assertTrue(WosCanonicalContractSupport.requiresSplitByEdition(raw));
    }

    @Test
    void sentinelMinus999NormalizesToMissing() {
        assertNull(WosCanonicalContractSupport.normalizeMetricValue(-999.0));
        assertEquals(1.5, WosCanonicalContractSupport.normalizeMetricValue(1.5));
    }

    @Test
    void precedencePicksGovernmentForAisAndRis() {
        assertEquals(
                WosSourceType.GOV_AIS_RIS,
                WosCanonicalContractSupport.selectCanonicalOperationalSource(
                        MetricType.AIS,
                        WosSourceType.OFFICIAL_WOS_EXTRACT,
                        WosSourceType.GOV_AIS_RIS
                )
        );
        assertEquals(
                WosSourceType.GOV_AIS_RIS,
                WosCanonicalContractSupport.selectCanonicalOperationalSource(
                        MetricType.RIS,
                        WosSourceType.GOV_AIS_RIS,
                        WosSourceType.OFFICIAL_WOS_EXTRACT
                )
        );
    }

    @Test
    void ifSourcePolicyAllowsOnlyOfficialExtract() {
        assertTrue(WosCanonicalContractSupport.isSourceAllowedForMetric(MetricType.IF, WosSourceType.OFFICIAL_WOS_EXTRACT));
        assertFalse(WosCanonicalContractSupport.isSourceAllowedForMetric(MetricType.IF, WosSourceType.GOV_AIS_RIS));
    }

    @Test
    void selectCanonicalOperationalSourceWithNullMetricTypeReturnsLeft() {
        WosSourceType result = WosCanonicalContractSupport.selectCanonicalOperationalSource(
                null, WosSourceType.GOV_AIS_RIS, WosSourceType.OFFICIAL_WOS_EXTRACT);
        assertEquals(WosSourceType.GOV_AIS_RIS, result);
    }

    @Test
    void selectCanonicalOperationalSourceWithIfAndOfficialExtract() {
        assertEquals(
                WosSourceType.OFFICIAL_WOS_EXTRACT,
                WosCanonicalContractSupport.selectCanonicalOperationalSource(
                        MetricType.IF, WosSourceType.GOV_AIS_RIS, WosSourceType.OFFICIAL_WOS_EXTRACT)
        );
        assertEquals(
                WosSourceType.OFFICIAL_WOS_EXTRACT,
                WosCanonicalContractSupport.selectCanonicalOperationalSource(
                        MetricType.IF, WosSourceType.OFFICIAL_WOS_EXTRACT, WosSourceType.GOV_AIS_RIS)
        );
    }

    @Test
    void selectCanonicalSourceFallsBackToLeftWhenNeitherIsGovForAis() {
        WosSourceType result = WosCanonicalContractSupport.selectCanonicalOperationalSource(
                MetricType.AIS, WosSourceType.OFFICIAL_WOS_EXTRACT, null);
        assertEquals(WosSourceType.OFFICIAL_WOS_EXTRACT, result);
    }

    @Test
    void issnNormalizationIsHyphenInsensitive() {
        assertEquals("12345678", WosCanonicalContractSupport.normalizeIssnToken("1234-5678"));
        assertEquals("12345678", WosCanonicalContractSupport.normalizeIssnToken(" 12345678 "));
        assertEquals("1234567X", WosCanonicalContractSupport.normalizeIssnToken("1234-567X"));
        assertNull(WosCanonicalContractSupport.normalizeIssnToken("********"));
        assertNull(WosCanonicalContractSupport.normalizeIssnToken("N/A"));
        assertNull(WosCanonicalContractSupport.normalizeIssnToken("1234-ABCD"));
    }

    @Test
    void identityKeyDeterministicForIssnKeyset() {
        String key1 = WosCanonicalContractSupport.buildIdentityKey(Set.of("12345678", "87654321"), "some title", 2024, "SCIE");
        String key2 = WosCanonicalContractSupport.buildIdentityKey(Set.of("87654321", "12345678"), "other title", 2020, "SSCI");
        assertEquals(key1, key2);
    }

    @Test
    void identityKeyIsMissingWhenNoIssnTokensProvided() {
        assertNull(WosCanonicalContractSupport.buildIdentityKey(Set.of(), "some title", 2024, "SCIE"));
    }

    @Test
    void titleNormalizationFoldsDiacriticsDeterministically() {
        assertEquals(
                "revista romana de fizica",
                WosCanonicalContractSupport.normalizeTitleFingerprint("Revista Romana de Fízică")
        );
    }

    @Test
    void normalizeTitleFingerprintReturnsNullForNullInput() {
        // Line 124: null check — removal causes NPE on null.toLowerCase()
        assertNull(WosCanonicalContractSupport.normalizeTitleFingerprint(null));
    }

    @Test
    void normalizeTitleFingerprintReturnsNullForAllSpecialChars() {
        // Line 132: isBlank check — removal causes blank string to be returned instead of null
        assertNull(WosCanonicalContractSupport.normalizeTitleFingerprint("---"));
    }

    @Test
    void normalizeMetricValueReturnsNullForNullInput() {
        // Line 75: null check — removal causes Double.compare(null, ...) → NPE
        assertNull(WosCanonicalContractSupport.normalizeMetricValue(null));
    }

    @Test
    void buildIdentityKeyWithNullTokenSetReturnsNull() {
        // Line 136: null check on normalizedIssnTokens — removal causes NPE on null.stream()
        assertNull(WosCanonicalContractSupport.buildIdentityKey(null, "some title", 2024, "SCIE"));
    }

    @Test
    void isSourceAllowedReturnsFalseWhenMetricTypeIsNull() {
        // Line 82: null check on metricType — removal causes NPE on MetricType.IF comparison
        assertFalse(WosCanonicalContractSupport.isSourceAllowedForMetric(null, WosSourceType.GOV_AIS_RIS));
    }

    @Test
    void isSourceAllowedReturnsFalseWhenSourceTypeIsNull() {
        // Line 82: null check on sourceType — removal causes true to be returned from `return true`
        assertFalse(WosCanonicalContractSupport.isSourceAllowedForMetric(MetricType.AIS, null));
    }

    @Test
    void requiresSplitReturnsFalseForSingleEdition() {
        // Line 71: size() > 1 mutation to size() >= 1 → any single edition returns true; test kills it
        assertFalse(WosCanonicalContractSupport.requiresSplitByEdition("SCIE"));
    }

    @Test
    void selectCanonicalSourceWithNullLeftAndNullMetricTypeReturnsRight() {
        // Line 97: left != null ? left : right — removal returns null when left is null
        WosSourceType result = WosCanonicalContractSupport.selectCanonicalOperationalSource(
                null, null, WosSourceType.OFFICIAL_WOS_EXTRACT);
        assertEquals(WosSourceType.OFFICIAL_WOS_EXTRACT, result);
    }
}
