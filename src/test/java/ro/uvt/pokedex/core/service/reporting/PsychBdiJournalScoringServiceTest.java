package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PsychBdiJournalScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;

    private PsychBdiJournalScoringService service;

    @BeforeEach
    void setUp() {
        lenient().when(lookupPort.maxAvailableYear()).thenReturn(2025);
        ro.uvt.pokedex.core.testsupport.ReportingLookupTestSupport.delegateForumLookupToIssn(lookupPort);
        service = new PsychBdiJournalScoringService(lookupPort);
    }

    private Indicator psychologyIndicator() {
        Domain domain = new Domain();
        domain.setName("Psychology");
        domain.setWosCategories(new java.util.ArrayList<>(List.of("PSYCHOLOGY - SSCI")));
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");
        return indicator;
    }

    private ScoringPublication publication(String subtype, String year) {
        return new ScoringPublication("pub-1", "eid-1", "forum-1", year + "-01-01", subtype, null,
                List.of("a1"), 1, "10.1000/pub-1", null, "Paper", 0, Set.of());
    }

    private ScholardexForumView forum() {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Journal");
        forum.setIssn("1234-5678");
        forum.setAggregationType("Journal");
        return forum;
    }

    private WoSRanking rankingWithIf(String categoryKey, int year, double ifValue, WoSRanking.Quarter q) {
        WoSRanking.Score score = new WoSRanking.Score();
        score.setIF(Map.of(year, ifValue));
        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQIF(Map.of(year, q));
        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-1");
        ranking.setIssn("1234-5678");
        ranking.setWebOfScienceCategoryIndex(Map.of(categoryKey, rank));
        ranking.setScore(score);
        return ranking;
    }

    @Test
    void strictQualifierIsSkippedWithReason() {
        // Psychology-category journal with IF>=p — I1's paper; must not double-count here.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678"))
                .thenReturn(List.of(rankingWithIf("PSYCHOLOGY - SSCI", 2024, 2.7, WoSRanking.Quarter.Q2)));

        Score s = service.getScore(publication("ar", "2024"), psychologyIndicator());

        assertEquals(0.0, s.getScore());
        assertEquals("SCORED_BY_STRICTER", s.getScoringInfo().get("zeroReason"));
    }

    @Test
    void borderlineDomainWosJournalScoresItsIfWithCategoryWos() {
        // The JMIR case: WoS-ranked but NOT in a psychology category → S = IF, category WOS.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678"))
                .thenReturn(List.of(rankingWithIf("MEDICAL INFORMATICS - ESCI", 2025, 2.4, WoSRanking.Quarter.Q3)));

        Score s = service.getScore(publication("ar", "2025"), psychologyIndicator());

        assertEquals(5.4, s.getScore(), 1e-9); // 3 + IF
        assertEquals("WOS", s.getCoreRankingEquivalent());
        assertNull(s.getScoringInfo().get("zeroReason"));
    }

    @Test
    void futureYearCarriesForwardToLatestRankedYear() {
        // 2026 paper, rankings end 2025 → carry-forward resolves IF-2025.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678"))
                .thenReturn(List.of(rankingWithIf("MEDICAL INFORMATICS - ESCI", 2025, 2.4, WoSRanking.Quarter.Q3)));

        Score s = service.getScore(publication("ar", "2026"), psychologyIndicator());

        assertEquals(5.4, s.getScore(), 1e-9); // 3 + IF-2025 carried forward
        assertEquals("WOS", s.getCoreRankingEquivalent());
    }

    @Test
    void inDomainLowIfJournalFallsToWosBranchNotStrict() {
        // Psychology-category journal but IF<p and below-median Q3 — I1 zeroes it, so it belongs here: 3+S via WOS.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678"))
                .thenReturn(List.of(rankingWithIf("PSYCHOLOGY - SSCI", 2024, 0.5, WoSRanking.Quarter.Q3)));

        Score s = service.getScore(publication("ar", "2024"), psychologyIndicator());

        assertEquals(3.5, s.getScore(), 1e-9); // 3 + IF(0.5)
        assertEquals("WOS", s.getCoreRankingEquivalent());
    }

    @Test
    void nonWosJournalWithTwoRecognizedBdisGetsCategoryBdi2() {
        // The Romanian Journal of Applied Psychology case: SCOPUS+DOAJ+ERIH, no WoS ranking.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("SCOPUS", "DOAJ", "ERIH"));

        Score s = service.getScore(publication("ar", "2024"), psychologyIndicator());

        assertEquals(3.0, s.getScore(), 1e-9); // flat 3 (IF = 0)
        assertEquals("BDI2", s.getCoreRankingEquivalent());
    }

    @Test
    void singleBdiMembershipGetsCategoryBdi1AndWosEditionsDoNotCount() {
        // ESCI is a WoS edition, not a "BDI other than WoS" — only SCOPUS counts here.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("SCOPUS", "ESCI", "OPENALEX"));

        Score s = service.getScore(publication("ar", "2024"), psychologyIndicator());

        assertEquals("BDI1", s.getCoreRankingEquivalent());
    }

    // ── Psihologie 2026 (OM 3019/2025, Comisia 28) — psihologie2026 flag ──

    private Indicator psychologyIndicator2026() {
        Indicator indicator = psychologyIndicator();
        indicator.setPsihologie2026(true);
        return indicator;
    }

    @Test
    void rules2026AcceptASingleRecognisedDatabase() {
        // "indexate într-una sau mai multe baze de date internaționale recunoscute": Scopus alone is enough.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("SCOPUS", "OPENALEX"));

        Score s = service.getScore(publication("ar", "2024"), psychologyIndicator2026());

        assertEquals(3.0, s.getScore(), 1e-9); // 3 + IF, IF = 0
        assertEquals("BDI", s.getCoreRankingEquivalent());
    }

    @Test
    void rules2026DoNotRecogniseDoajForPsychology() {
        // DOAJ is on the Educational Sciences and Sport lists only; a DOAJ-only journal scores nothing here.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("DOAJ", "OPENALEX"));

        Score s = service.getScore(publication("ar", "2024"), psychologyIndicator2026());

        assertEquals(0.0, s.getScore());
        assertNull(s.getCoreRankingEquivalent());
    }

    @Test
    void rules2026StillSkipWhatTheStrictIndicatorsCountAndStillPreferTheWosBranch() {
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678"))
                .thenReturn(List.of(rankingWithIf("PSYCHOLOGY - SSCI", 2024, 2.7, WoSRanking.Quarter.Q2)));
        Score strict = service.getScore(publication("ar", "2024"), psychologyIndicator2026());
        assertEquals("SCORED_BY_STRICTER", strict.getScoringInfo().get("zeroReason"));

        when(lookupPort.getRankingsByIssn("1234-5678"))
                .thenReturn(List.of(rankingWithIf("PSYCHOLOGY - SSCI", 2024, 0.5, WoSRanking.Quarter.Q3)));
        Score lowIf = service.getScore(publication("ar", "2024"), psychologyIndicator2026());
        assertEquals(3.5, lowIf.getScore(), 1e-9);
        assertEquals("WOS", lowIf.getCoreRankingEquivalent());
    }

    @Test
    void rules2026SkipAnEsciJournalOfTheDomainThatTheStrictIndicatorsNowCount() {
        // PSYCHOLOGY - ESCI with IF >= 1: I1A/I1B/I5 count it under the 2026 annex, so it must not score here.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678"))
                .thenReturn(List.of(rankingWithIf("PSYCHOLOGY - ESCI", 2024, 1.4, WoSRanking.Quarter.Q3)));

        Score flagged = service.getScore(publication("ar", "2024"), psychologyIndicator2026());
        assertEquals(0.0, flagged.getScore());
        assertEquals("SCORED_BY_STRICTER", flagged.getScoringInfo().get("zeroReason"));

        // …while the 2016 indicator, whose strict sibling ignores ESCI, keeps scoring it as a WoS journal.
        Score frozen = service.getScore(publication("ar", "2024"), psychologyIndicator());
        assertEquals(4.4, frozen.getScore(), 1e-9);
        assertEquals("WOS", frozen.getCoreRankingEquivalent());
    }

    @Test
    void rules2026ScoreAWebOfScienceJournalWithoutAnyImpactFactor() {
        // ESCI before JCR 2023 (or AHCI): "IF mai mic decât p" with IF = 0, so 3 points.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.isForumInEsci("forum-1", 2019)).thenReturn(true);

        Score s = service.getScore(publication("ar", "2019"), psychologyIndicator2026());

        assertEquals(3.0, s.getScore(), 1e-9);
        assertEquals("WOS", s.getCoreRankingEquivalent());
    }

    @Test
    void the2016RulesDoNotAskForWebOfScienceMembership() {
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of());

        assertEquals(0.0, service.getScore(publication("ar", "2019"), psychologyIndicator()).getScore());
        org.mockito.Mockito.verify(lookupPort, org.mockito.Mockito.never())
                .isForumInEsci(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ── Științe ale Educației 2026 — stiinteEducatiei2026 flag ──

    private Indicator educationIndicator2026() {
        Domain domain = new Domain();
        domain.setName("Educational Sciences");
        domain.setWosCategories(new java.util.ArrayList<>(List.of("EDUCATION & EDUCATIONAL RESEARCH - SSCI")));
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        indicator.setStiinteEducatiei2026(true);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");
        return indicator;
    }

    @Test
    void educationalSciencesLeaveToTheStrictIndicatorsEverythingFromTheirOwnThresholdUp() {
        // IF 0.4 is below psychology's p and above educational sciences' p = 0.10.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(
                rankingWithIf("EDUCATION & EDUCATIONAL RESEARCH - ESCI", 2024, 0.4, WoSRanking.Quarter.Q4)));

        Score s = service.getScore(publication("ar", "2024"), educationIndicator2026());

        assertEquals(0.0, s.getScore());
        assertEquals("SCORED_BY_STRICTER", s.getScoringInfo().get("zeroReason"));
    }

    @Test
    void educationalSciencesHaveNoAboveMedianException() {
        // Q1 with IF 0.05: psychology's exception would hand it to I1; here it stays an I2 article.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(
                rankingWithIf("EDUCATION & EDUCATIONAL RESEARCH - SSCI", 2024, 0.05, WoSRanking.Quarter.Q1)));

        Score s = service.getScore(publication("ar", "2024"), educationIndicator2026());

        assertEquals(3.05, s.getScore(), 1e-9);
        assertEquals("WOS", s.getCoreRankingEquivalent());
    }

    @Test
    void educationalSciencesRecogniseDoaj() {
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("DOAJ", "OPENALEX"));

        Score s = service.getScore(publication("ar", "2024"), educationIndicator2026());

        assertEquals(3.0, s.getScore(), 1e-9);
        assertEquals("BDI", s.getCoreRankingEquivalent());
    }

    @Test
    void aJournalOfAnotherDomainIsScoredHereWhateverItsImpactFactor() {
        // The platform's reading of "domenii de graniță": a WoS journal outside the domain's categories is
        // not counted by the strict indicators and scores 3 + IF here.
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(
                rankingWithIf("ENVIRONMENTAL SCIENCES - SCIE", 2024, 3.3, WoSRanking.Quarter.Q2)));

        Score s = service.getScore(publication("ar", "2024"), educationIndicator2026());

        assertEquals(6.3, s.getScore(), 1e-9);
        assertEquals("WOS", s.getCoreRankingEquivalent());
    }

    @Test
    void unindexedVenueAndNonArticleGetNoScore() {
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of());
        Score unindexed = service.getScore(publication("ar", "2024"), psychologyIndicator());
        assertEquals(0.0, unindexed.getScore());
        assertNull(unindexed.getCoreRankingEquivalent());

        Score proceedings = service.getScore(publication("cp", "2024"), psychologyIndicator());
        assertEquals(0.0, proceedings.getScore());
        assertEquals("VENUE_TYPE_MISMATCH", proceedings.getScoringInfo().get("zeroReason"));
    }
}
