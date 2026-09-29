package ro.uvt.pokedex.core.service.reporting;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * OM 3.019/2025, Comisia 25 — the three journal-shaped scorers: I.1 (impact factor in any edition), I.2
 * (journals without impact factor) and I.9 (a citation priced by the citing journal).
 */
@ExtendWith(MockitoExtension.class)
class Comisia25ScoringTest {

    @Mock
    private ReportingLookupPort lookupPort;

    private ImpactFactorJournalScoringService impactFactor;
    private SociologyIndexedJournalScoringService indexedJournal;
    private CitingImpactFactorScoringService citing;

    @BeforeEach
    void setUp() {
        lenient().when(lookupPort.maxAvailableYear()).thenReturn(2025);
        ro.uvt.pokedex.core.testsupport.ReportingLookupTestSupport.delegateForumLookupToIssn(lookupPort);
        impactFactor = new ImpactFactorJournalScoringService(lookupPort, new SimpleMeterRegistry());
        indexedJournal = new SociologyIndexedJournalScoringService(lookupPort);
        citing = new CitingImpactFactorScoringService(lookupPort);
    }

    private static Indicator indicator(String domainName, boolean flagged, String... categories) {
        Domain domain = new Domain();
        domain.setId(domainName);
        domain.setName(domainName);
        domain.setWosCategories(new ArrayList<>(List.of(categories)));
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        indicator.setSociologie2026(flagged ? Boolean.TRUE : null);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");
        return indicator;
    }

    private static Indicator everyJournal() {
        return indicator("ALL", true, "*");
    }

    private static Indicator core() {
        return indicator("Sociologie 2026 - nucleu", true,
                "SOCIOLOGY - SSCI", "SOCIOLOGY - ESCI", "CULTURAL STUDIES - AHCI", "SOCIAL WORK - SSCI");
    }

    private static ScoringPublication publication(String subtype, String year) {
        return new ScoringPublication("pub-1", "eid-1", "forum-1", year + "-01-01", subtype, null,
                List.of("a1"), 1, "10.1000/pub-1", null, "Paper", 0, Set.of());
    }

    private static ScholardexForumView forum() {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Journal");
        forum.setIssn("1234-5678");
        forum.setAggregationType("Journal");
        return forum;
    }

    private static WoSRanking ranking(String categoryKey, Map<Integer, Double> impactFactors) {
        WoSRanking.Score score = new WoSRanking.Score();
        score.setIF(impactFactors);
        WoSRanking.Rank rank = new WoSRanking.Rank();
        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-1");
        ranking.setIssn("1234-5678");
        ranking.setWebOfScienceCategoryIndex(Map.of(categoryKey, rank));
        ranking.setScore(score);
        return ranking;
    }

    private void journalHas(String categoryKey, Map<Integer, Double> impactFactors) {
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking(categoryKey, impactFactors)));
    }

    // ── I.1 ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void anImpactFactorCountsInTheHumanitiesEdition() {
        // Religion, Philosophy, History … exist in AHCI only; every other indicator filters them out.
        journalHas("RELIGION - AHCI", Map.of(2024, 0.8));

        assertEquals(0.8, impactFactor.getScore(publication("ar", "2024"), everyJournal()).getScore(), 1e-9);
        assertEquals(0.0, impactFactor.getScore(publication("ar", "2024"), indicator("ALL", false, "*")).getScore(),
                "without the flag the SCIE/SSCI filter stays");
    }

    @Test
    void anImpactFactorCountsInTheEmergingSourcesEdition() {
        journalHas("SOCIAL WORK - ESCI", Map.of(2024, 1.1));

        assertEquals(1.1, impactFactor.getScore(publication("ar", "2024"), everyJournal()).getScore(), 1e-9);
    }

    @Test
    void theCoreDomainCountsOnlyTheCategoriesItLists() {
        journalHas("SOCIOLOGY - ESCI", Map.of(2024, 1.4));
        assertEquals(1.4, impactFactor.getScore(publication("ar", "2024"), core()).getScore(), 1e-9);

        journalHas("ECONOMICS - SSCI", Map.of(2024, 3.0));
        assertEquals(0.0, impactFactor.getScore(publication("ar", "2024"), core()).getScore(),
                "a related category is not part of the core");
        assertEquals(3.0, impactFactor.getScore(publication("ar", "2024"), everyJournal()).getScore(), 1e-9);
    }

    @Test
    void anArticleNewerThanThePublishedDataTakesTheLatestImpactFactor() {
        journalHas("SOCIOLOGY - SSCI", Map.of(2024, 2.0));

        assertEquals(2.0, impactFactor.getScore(publication("ar", "2026"), everyJournal()).getScore(), 1e-9);
    }

    @Test
    void anOlderArticleWithoutAnImpactFactorInItsYearGetsNone() {
        // The journal had an impact factor in 2015 and none in 2019: a 2019 article is not an I.1 article.
        journalHas("SOCIOLOGY - SSCI", Map.of(2015, 2.0));

        Score score = impactFactor.getScore(publication("ar", "2019"), everyJournal());

        assertEquals(0.0, score.getScore());
    }

    @Test
    void aBookIsNotAJournalArticle() {
        lenient().when(lookupPort.getForum("forum-1")).thenReturn(forum());

        Score score = impactFactor.getScore(publication("bk", "2024"), everyJournal());

        assertEquals(0.0, score.getScore());
        assertEquals("VENUE_TYPE_MISMATCH", score.getScoringInfo().get("zeroReason"));
    }

    // ── I.2 ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void anArticleWithAnImpactFactorIsLeftToTheFirstIndicator() {
        journalHas("RELIGION - AHCI", Map.of(2024, 0.3));
        lenient().when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("SCOPUS", "AHCI"));

        Score score = indexedJournal.getScore(publication("ar", "2024"), everyJournal());

        assertEquals(0.0, score.getScore());
        assertEquals("SCORED_BY_STRICTER", score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void aScopusJournalWithoutImpactFactorScoresFour() {
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("SCOPUS", "OPENALEX"));

        Score score = indexedJournal.getScore(publication("ar", "2020"), everyJournal());

        assertEquals(4.0, score.getScore());
        assertEquals("SCOPUS", score.getCoreRankingEquivalent());
    }

    @Test
    void threeRecognisedDatabasesScoreTwoAndWebOfScienceIsOneOfThem() {
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        // ESCI and AHCI are two editions of ONE database.
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("ESCI", "AHCI", "DOAJ", "ERIH"));

        Score score = indexedJournal.getScore(publication("ar", "2020"), everyJournal());

        assertEquals(2.0, score.getScore());
        assertEquals("BDI3", score.getCoreRankingEquivalent());
    }

    @Test
    void twoRecognisedDatabasesAreNotEnough() {
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("ESCI", "AHCI", "DOAJ", "CNCS"));

        Score score = indexedJournal.getScore(publication("ar", "2020"), everyJournal());

        assertEquals(0.0, score.getScore());
        assertNull(score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void aProceedingsPaperIsNotCountedAsAJournalArticle() {
        Score score = indexedJournal.getScore(publication("cp", "2020"), everyJournal());

        assertEquals(0.0, score.getScore());
        assertEquals("VENUE_TYPE_MISMATCH", score.getScoringInfo().get("zeroReason"));
    }

    // ── I.9 ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aCitationIsPricedByTheImpactFactorOfTheCitingJournal() {
        journalHas("ECONOMICS - SSCI", Map.of(2023, 1.5));

        Score score = citing.getScore(publication("ar", "2023"), everyJournal());

        assertEquals(0.2 + 4 * 1.5, score.getScore(), 1e-9);
        assertEquals("IF", score.getCoreRankingEquivalent());
    }

    @Test
    void aCitationFromASourceWithoutImpactFactorIsWorthTheFloor() {
        when(lookupPort.getForum("forum-1")).thenReturn(forum());
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());

        Score journal = citing.getScore(publication("ar", "2023"), everyJournal());
        Score chapter = citing.getScore(publication("ch", "2023"), everyJournal());

        assertEquals(0.2, journal.getScore(), 1e-9);
        assertEquals(0.2, chapter.getScore(), 1e-9);
        assertEquals("NO_IF", chapter.getCoreRankingEquivalent());
        assertTrue(chapter.getScore() > 0, "S must stay positive or the engine never evaluates the formula");
    }

    // ── the rules themselves ────────────────────────────────────────────────────────────────────────

    @Test
    void onlyAFlaggedIndicatorFollowsTheRules() {
        assertTrue(Comisia25Rules.of(everyJournal()).isPresent());
        assertTrue(Comisia25Rules.of(indicator("ALL", false, "*")).isEmpty());
        assertTrue(Comisia25Rules.of(null).isEmpty());
    }

    @Test
    void aStoredDomainNeverAdmitsAnythingByItself() {
        Domain stored = core().getDomain();

        assertEquals(false, Comisia25Rules.admits(stored, "SOCIOLOGY - ESCI"), "only the any-edition copy does");
        assertTrue(Comisia25Rules.admits(Comisia25Rules.anyEdition(stored), "SOCIOLOGY - ESCI"));
        assertEquals(false, Comisia25Rules.admits(Comisia25Rules.anyEdition(stored), "ECONOMICS - SSCI"));
        assertEquals(List.of("SOCIOLOGY - SSCI", "SOCIOLOGY - ESCI", "CULTURAL STUDIES - AHCI", "SOCIAL WORK - SSCI"),
                stored.getWosCategories(), "the stored domain is left as it was");
    }
}
