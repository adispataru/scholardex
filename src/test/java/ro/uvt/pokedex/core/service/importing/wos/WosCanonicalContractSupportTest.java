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
    void issnNormalizationIsHyphenInsensitive() {
        assertEquals("12345678", WosCanonicalContractSupport.normalizeIssnToken("1234-5678"));
        assertEquals("12345678", WosCanonicalContractSupport.normalizeIssnToken(" 12345678 "));
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
}
