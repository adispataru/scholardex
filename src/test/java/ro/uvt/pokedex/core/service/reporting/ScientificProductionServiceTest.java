package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.Arrays;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class ScientificProductionServiceTest {

    @Mock
    private ScoringFactoryService scoringFactoryService;

    @Mock
    private ScoringService scoringService;
    @Mock
    private ScholardexPublicationDblpEvidenceRepository dblpEvidenceRepository;
    @Mock
    private ReportingLookupPort reportingLookupPort;
    @Mock
    private PublicationCountryAuthorCountService publicationCountryAuthorCountService;

    // Real evaluator (no MVEL behavior to mock) — @InjectMocks picks it up via the
    // constructor signature.
    @org.mockito.Spy
    private ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator formulaEvaluator =
            new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator();

    @InjectMocks
    private ScientificProductionService scientificProductionService;


    @Test
    void productionScoreGenericCountAssignsOnePerPublicationAndTotalSize() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");
        List<ScoringPublicationReadModel> publications = List.of(
                publication("p1", null, null, null, null, "Paper 1", List.of("a1")),
                publication("p2", null, null, null, null, "Paper 2", List.of("a1", "a2"))
        );

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(publications, indicator);

        assertEquals(1.0, result.get("Paper 1").getScore(), 0.0001);
        assertEquals(1.0, result.get("Paper 1").getAuthorScore(), 0.0001);
        assertEquals(1.0, result.get("Paper 2").getScore(), 0.0001);
        assertEquals(1.0, result.get("Paper 2").getAuthorScore(), 0.0001);
        assertEquals(2.0, result.get("total").getAuthorScore(), 0.0001);
        verify(scoringFactoryService, never()).getScoringService(org.mockito.ArgumentMatchers.any(String.class));
    }

    @Test
    void detailedVariantCollectsGateDroppedPublicationsWithoutChangingTheScoresMap() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        ScoringPublication scored = publication("p-ok", "f-ok", "2022-01-01", "ar", "ar",
                "Scored Paper", List.of("a1"));
        ScoringPublication dropped = publication("p-wseas", "f-wseas", "2021-01-01", "ar", "ar",
                "Excluded Venue Paper", List.of("a1"));
        Score zero = new Score();
        zero.getScoringInfo().put("zeroReason", "EXCLUDED_VENUE");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(scored, indicator)).thenReturn(score(3.0));
        when(scoringService.getScore(dropped, indicator)).thenReturn(zero);

        ScientificProductionService.ScoredProductionResult result =
                scientificProductionService.calculateScientificProductionScoreDetailed(
                        List.of(scored, dropped), indicator);

        // Regression pin: the scores map behaves exactly as before — dropped items absent.
        assertEquals(3.0, result.scores().get("Scored Paper").getAuthorScore(), 0.0001);
        assertEquals(3.0, result.scores().get("total").getAuthorScore(), 0.0001);
        org.junit.jupiter.api.Assertions.assertFalse(result.scores().containsKey("Excluded Venue Paper"));
        // The dropped item is reported separately, reason intact.
        assertEquals("EXCLUDED_VENUE",
                result.excluded().get("Excluded Venue Paper").getScoringInfo().get("zeroReason"));
    }

    @Test
    void nonResearchSubtypeIsDroppedWithItsReasonBeforeTheScorerRuns() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        // 'ed' (editorial) fails the universal research-contribution gate.
        ScoringPublication editorial = publication("p-ed", "f-1", "2022-01-01", "ed", "ed",
                "An Editorial", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        ScientificProductionService.ScoredProductionResult result =
                scientificProductionService.calculateScientificProductionScoreDetailed(
                        List.of(editorial), indicator);

        assertEquals("NON_RESEARCH_SUBTYPE",
                result.excluded().get("An Editorial").getScoringInfo().get("zeroReason"));
        verify(scoringService, never()).getScore(editorial, indicator);
    }

    @Test
    void publicationScoreDisplaysPublicationYearNotTheRankingYear() {
        // A fallback scorer (SCOPUS C/D, LNCS, SENSE) sets the score year to a constant ranking year (e.g. 2023).
        // The displayed item year must be the publication's OWN cover-date year (2020), not the ranking year.
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        ScoringPublication pub = publication("p-2020", "forum-1", "2020-01-01", "ar", "ar",
                "Decentralized Cloud Orchestration", List.of("a1"));
        Score rankingScore = score(2.0);
        rankingScore.setYear(2023); // the constant ranking/fallback year a scorer would set
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(pub, indicator)).thenReturn(rankingScore);

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(pub), indicator);

        assertEquals(2020, result.get("Decentralized Cloud Orchestration").getYear());
    }

    @Test
    void topABGateExcludes2026WorkshopByCategoryButPreservesNormalAndBookViaPointsProxy() {
        // H79: the 2026 "top A*/A/B" formula gates on topAB (category) instead of S>=4. A 2026 workshop of an A*
        // conference is category C but earns 6 points; the old S>=4 proxy wrongly counted it. Normal B papers and
        // SENSE-C books (category label C, but NOT workshop-adjusted) must be unaffected — they keep the S>=4 path.
        Indicator indicator = indicator("PUBLICATIONS", "(topAB && !feeJournal) ? (S/max(N-2, 1)) : 0");

        ScoringPublication ws = publication("p-ws", "f-ws", "2023-01-01", "cp", "cp",
                "A* Workshop Paper", List.of("a1", "a2", "a3"));
        Score wsScore = score(6.0);                    // inflated workshop points
        wsScore.setCoreRankingEquivalent("C");         // 2026 relabel: A* workshop -> category C
        wsScore.getScoringInfo().put("workshopAdjusted", true);

        ScoringPublication b = publication("p-b", "f-b", "2023-01-01", "ar", "ar",
                "Category B Journal", List.of("a1", "a2", "a3"));
        Score bScore = score(4.0);
        bScore.setCoreRankingEquivalent("B");          // normal B, not a workshop

        ScoringPublication book = publication("p-book", "f-book", "2023-01-01", "bk", "bk",
                "SENSE C Book", List.of("a1", "a2", "a3"));
        Score bookScore = score(4.0);
        bookScore.setCoreRankingEquivalent("C");       // SENSE-C book: category label C but 4 pts, NOT workshop

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(ws, indicator)).thenReturn(wsScore);
        when(scoringService.getScore(b, indicator)).thenReturn(bScore);
        when(scoringService.getScore(book, indicator)).thenReturn(bookScore);

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(
                List.of(ws, b, book), indicator);

        // Workshop (category C) is excluded from the top -> authorScore 0 (its forumScore 6 keeps it in the map).
        assertEquals(0.0, result.get("A* Workshop Paper").getAuthorScore(), 1e-9);
        // Normal B journal + SENSE-C book both still count via the preserved S>=4 path: S/max(N-2,1) = 4/1 each.
        assertEquals(4.0, result.get("Category B Journal").getAuthorScore(), 1e-9);
        assertEquals(4.0, result.get("SENSE C Book").getAuthorScore(), 1e-9);
        assertEquals(8.0, result.get("total").getAuthorScore(), 1e-9);
    }

    @Test
    void feeJournalGateZeroIsExplainedByTheCounterfactualProbe() {
        // 2026 APC exclusion: the formula zeroes fee journals outright. The counterfactual re-eval with
        // feeJournal=false turns positive, proving the APC gate alone caused the zero -> FEE_JOURNAL stamp.
        Indicator indicator = indicator("PUBLICATIONS", "feeJournal ? 0 : (S/max(N-2, 1))");
        ScoringPublication pub = publication("p-apc", "f-apc", "2026-01-01", "ar", "ar",
                "Gold OA Paper", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(pub, indicator)).thenReturn(score(4.0));
        when(reportingLookupPort.isFeeJournal("f-apc")).thenReturn(true);

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(pub), indicator);

        // The item stays IN the scores map (venue points survive) — only the author score is zeroed.
        assertEquals(0.0, result.get("Gold OA Paper").getAuthorScore(), 1e-9);
        assertEquals(4.0, result.get("Gold OA Paper").getScore(), 1e-9);
        assertEquals("FEE_JOURNAL", result.get("Gold OA Paper").getScoringInfo().get("zeroReason"));
        assertEquals(0.0, result.get("total").getAuthorScore(), 1e-9);
    }

    @Test
    void twoGatesFailingTogetherStampMultipleGatesNotASingleReason() {
        // Category C fails topAB AND the venue is fee-gated: no single probe explains the zero, but
        // the combined all-favorable probe does — MULTIPLE_GATES replaces the old honest-but-opaque
        // unstamped state so the drilldown says "several conditions" instead of a bare formula cutoff.
        Indicator indicator = indicator("PUBLICATIONS", "(topAB && !feeJournal) ? (S/max(N-2, 1)) : 0");
        ScoringPublication pub = publication("p-apc-c", "f-apc-c", "2026-01-01", "ar", "ar",
                "Gold OA Category C Paper", List.of("a1"));
        Score cScore = score(3.0);
        cScore.setCoreRankingEquivalent("C"); // below the top A*/A/B cut AND below the S>=4 proxy
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(pub, indicator)).thenReturn(cScore);
        when(reportingLookupPort.isFeeJournal("f-apc-c")).thenReturn(true);

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(pub), indicator);

        assertEquals(0.0, result.get("Gold OA Category C Paper").getAuthorScore(), 1e-9);
        assertEquals("MULTIPLE_GATES",
                result.get("Gold OA Category C Paper").getScoringInfo().get("zeroReason"));
    }

    @Test
    void categoryDConferenceZeroedByTheThresholdGateStampsScoreBelowFormulaThreshold() {
        // The Info_B_Conferințe D-gate (S > 1 ? … : 0): a category-D conference paper carries 1 venue
        // point but is excluded from the total per the standard/FV template. The generic S probe must
        // stamp SCORE_BELOW_FORMULA_THRESHOLD so the drilldown explains the exclusion.
        Indicator indicator = indicator("PUBLICATIONS", "S > 1 ? S/max(N-2, 1) : 0");
        ScoringPublication pub = publication("p-d-conf", "f-d-conf", "2019-01-01", "cp", "cp",
                "Category D Conference Paper", List.of("a1"));
        Score dScore = score(1.0);
        dScore.setCoreRankingEquivalent("D");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(pub, indicator)).thenReturn(dScore);
        when(reportingLookupPort.isFeeJournal("f-d-conf")).thenReturn(false);

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(pub), indicator);

        assertEquals(0.0, result.get("Category D Conference Paper").getAuthorScore(), 1e-9);
        assertEquals(1.0, result.get("Category D Conference Paper").getScore(), 1e-9);
        assertEquals("SCORE_BELOW_FORMULA_THRESHOLD",
                result.get("Category D Conference Paper").getScoringInfo().get("zeroReason"));
    }

    @Test
    void docTypeAndCategoryVariablesSplitEligibilityFormulasByDocumentTypeAndClass() {
        // PD 2026: the director standard counts article/review only; a journal proceedings paper ("cp")
        // is a candidate that the formula excludes via docType. category carries the scorer's class label.
        Indicator indicator = indicator("PUBLICATIONS",
                "(docType == \"ar\" || docType == \"re\") && (category == \"A*\" || category == \"A\") ? 1 : 0");
        ScoringPublication article = publication("p-ar", "f-1", "2023-01-01", "ar", "ar",
                "Article In A-Class Venue", List.of("a1"));
        ScoringPublication proceedings = publication("p-cp", "f-1", "2023-01-01", "cp", "cp",
                "Proceedings Paper In A-Class Venue", List.of("a1"));
        Score aClass = score(1.0);
        aClass.setCoreRankingEquivalent("A");
        Score aClass2 = score(1.0);
        aClass2.setCoreRankingEquivalent("A");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(article, indicator)).thenReturn(aClass);
        when(scoringService.getScore(proceedings, indicator)).thenReturn(aClass2);

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(
                List.of(article, proceedings), indicator);

        assertEquals(1.0, result.get("Article In A-Class Venue").getAuthorScore(), 1e-9);
        assertEquals(0.0, result.get("Proceedings Paper In A-Class Venue").getAuthorScore(), 1e-9);
        assertEquals(1.0, result.get("total").getAuthorScore(), 1e-9);
    }

    @Test
    void distinctForumsSelectorTotalsDistinctVenuesNotWorks() {
        // PD 2026 mentor Q2-diversity: two qualifying works in the SAME journal count as ONE venue;
        // the items keep their per-work scores in the map.
        Indicator indicator = indicator("PUBLICATIONS", "Q == \"Q2\" ? 1 : 0");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "DISTINCT_FORUMS");
        ScoringPublication sameJournalA = publication("p1", "f-same", "2020-01-01", "ar", "ar",
                "Q2 Paper One", List.of("a1"));
        ScoringPublication sameJournalB = publication("p2", "f-same", "2021-01-01", "ar", "ar",
                "Q2 Paper Two", List.of("a1"));
        ScoringPublication otherJournal = publication("p3", "f-other", "2022-01-01", "ar", "ar",
                "Q2 Paper Three", List.of("a1"));
        ScoringPublication q1Paper = publication("p4", "f-q1", "2022-01-01", "ar", "ar",
                "Q1 Paper", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(sameJournalA, indicator)).thenReturn(quarterScore("Q2"));
        when(scoringService.getScore(sameJournalB, indicator)).thenReturn(quarterScore("Q2"));
        when(scoringService.getScore(otherJournal, indicator)).thenReturn(quarterScore("Q2"));
        when(scoringService.getScore(q1Paper, indicator)).thenReturn(quarterScore("Q1"));

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(
                List.of(sameJournalA, sameJournalB, otherJournal, q1Paper), indicator);

        // 3 positive works across 2 distinct journals -> total 2; the Q1 paper scores 0 and adds no venue.
        assertEquals(1.0, result.get("Q2 Paper One").getAuthorScore(), 1e-9);
        assertEquals(1.0, result.get("Q2 Paper Two").getAuthorScore(), 1e-9);
        assertEquals(2.0, result.get("total").getAuthorScore(), 1e-9);
    }

    private Score quarterScore(String quarter) {
        Score s = score(1.0);
        s.setQuarter(quarter);
        return s;
    }

    @Test
    void perForumCapSelectorKeepsAtMostTwoHighestScoringItemsPerForum() {
        // FSP I9/I10: "se pot puncta cumulat cel mult două contribuţii/ediţie conferinţă". Three positive
        // papers in the SAME proceedings forum are capped at the two highest; a paper in a different
        // edition is unaffected. The dropped item leaves the scores map and carries OVER_PER_FORUM_CAP.
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "PER_FORUM_CAP_2");
        ScoringPublication sameEditionLow = publication("p1", "f-edition", "2020-01-01", "cp", "cp",
                "Edition Paper Low", List.of("a1"));
        ScoringPublication sameEditionHigh = publication("p2", "f-edition", "2021-01-01", "cp", "cp",
                "Edition Paper High", List.of("a1"));
        ScoringPublication sameEditionMid = publication("p3", "f-edition", "2021-01-01", "cp", "cp",
                "Edition Paper Mid", List.of("a1"));
        ScoringPublication otherEdition = publication("p4", "f-other", "2022-01-01", "cp", "cp",
                "Other Edition Paper", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(sameEditionLow, indicator)).thenReturn(score(1.0));
        when(scoringService.getScore(sameEditionHigh, indicator)).thenReturn(score(3.0));
        when(scoringService.getScore(sameEditionMid, indicator)).thenReturn(score(2.0));
        when(scoringService.getScore(otherEdition, indicator)).thenReturn(score(4.0));

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(
                List.of(sameEditionLow, sameEditionHigh, sameEditionMid, otherEdition), indicator);

        // Same edition keeps the two highest (3 + 2); the lowest (1) is dropped. Other edition adds 4.
        assertEquals(3.0, result.get("Edition Paper High").getAuthorScore(), 1e-9);
        assertEquals(2.0, result.get("Edition Paper Mid").getAuthorScore(), 1e-9);
        assertEquals(4.0, result.get("Other Edition Paper").getAuthorScore(), 1e-9);
        assertNull(result.get("Edition Paper Low"));
        assertEquals(3.0 + 2.0 + 4.0, result.get("total").getAuthorScore(), 1e-9);
    }

    @Test
    void perForumCapSelectorReportsDroppedItemWithReasonInDetailedResult() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "PER_FORUM_CAP_2");
        ScoringPublication a = publication("p1", "f-edition", "2020-01-01", "cp", "cp", "A", List.of("a1"));
        ScoringPublication b = publication("p2", "f-edition", "2021-01-01", "cp", "cp", "B", List.of("a1"));
        ScoringPublication c = publication("p3", "f-edition", "2021-01-01", "cp", "cp", "C", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(a, indicator)).thenReturn(score(1.0));
        when(scoringService.getScore(b, indicator)).thenReturn(score(3.0));
        when(scoringService.getScore(c, indicator)).thenReturn(score(2.0));

        ScientificProductionService.ScoredProductionResult result =
                scientificProductionService.calculateScientificProductionScoreDetailed(List.of(a, b, c), indicator);

        assertEquals("OVER_PER_FORUM_CAP", result.excluded().get("A").getScoringInfo().get("zeroReason"));
        assertFalse(result.scores().containsKey("A"));
        assertEquals(5.0, result.scores().get("total").getAuthorScore(), 1e-9);
    }

    @Test
    void physicsIndicatorDividesAisByNef() {
        // H65: I = ΣAISᵢ/Nefᵢ. 6 authors → Nef = (6+5)/2 = 5.5; AIS (=S) = 4.0 → 4/5.5.
        Indicator indicator = indicator("PUBLICATIONS", "S/Nef");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "AIS");
        ScoringPublication pub = publication("p1", "f1", "2022-01-01", "ar", "ar", "Six Authors",
                List.of("a1", "a2", "a3", "a4", "a5", "a6"));
        when(scoringFactoryService.getScoringService("AIS")).thenReturn(scoringService);
        when(scoringService.getScore(pub, indicator)).thenReturn(score(4.0));

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(pub), indicator);

        assertEquals(4.0 / 5.5, result.get("total").getAuthorScore(), 1e-9);
    }

    @Test
    void nonFinitePerPublicationScoreIsGuardedToZeroNotInfinity() {
        // A 0-author publication under an "S/N" formula divides by zero -> Infinity, which would poison the whole
        // indicator total. The guard makes that publication contribute 0 so the remaining valid publications still sum.
        // (Surfaced by H77 provisional scoring, where a corrupt Infinity journal metric showed ∞ for a department.)
        Indicator indicator = indicator("PUBLICATIONS", "S/N");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "AIS");
        ScoringPublication zeroAuthors = publication("p-0a", "f1", "2022-01-01", "ar", "ar", "Zero Authors", List.of());
        ScoringPublication normal = publication("p-ok", "f1", "2022-01-01", "ar", "ar", "Normal", List.of("a1"));
        when(scoringFactoryService.getScoringService("AIS")).thenReturn(scoringService);
        when(scoringService.getScore(zeroAuthors, indicator)).thenReturn(score(4.0));
        when(scoringService.getScore(normal, indicator)).thenReturn(score(4.0));

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(
                List.of(zeroAuthors, normal), indicator);

        assertTrue(Double.isFinite(result.get("total").getAuthorScore()));
        assertEquals(4.0, result.get("total").getAuthorScore(), 0.0001); // 0 (guarded S/0) + 4/1
    }

    @Test
    void impactScoreGenericCountAssignsOnePerPublicationAndTotalSize() {
        Indicator indicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");
        ScoringPublication cited = publication("cited", null, null, null, null, "Cited", List.of("a1"));
        List<ScoringPublicationReadModel> citingPublications = List.of(
                publication("cp1", null, null, null, null, "Citing 1", List.of("b1")),
                publication("cp2", null, null, null, null, "Citing 2", List.of("b2"))
        );

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(cited, citingPublications, indicator);

        assertEquals(1.0, result.get("Citing 1").getScore(), 0.0001);
        assertEquals(1.0, result.get("Citing 2").getAuthorScore(), 0.0001);
        assertEquals(2.0, result.get("total").getAuthorScore(), 0.0001);
        verify(scoringFactoryService, never()).getScoringService(org.mockito.ArgumentMatchers.any(String.class));
    }

    @Test
    void cachedBasePathMatchesLegacyPathForCitationsAndExcludeSelf() {
        Indicator citations = indicator("CITATIONS", "S");
        Indicator citationsExcludeSelf = indicator("CITATIONS_EXCLUDE_SELF", "S");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "cited-1", List.of("a1", "a2"));
        ScoringPublication citingA = publication("cp-1", null, null, null, null, "cp-1", List.of("b1"));
        ScoringPublication citingB = publication("cp-2", null, null, null, null, "cp-2", List.of("b2"));
        List<ScoringPublicationReadModel> citingPublications = List.of(citingA, citingB);

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(citingA, citations)).thenReturn(score(2.0));
        when(scoringService.getScore(citingB, citations)).thenReturn(score(3.0));
        when(scoringService.getScore(citingA, citationsExcludeSelf)).thenReturn(score(2.0));
        when(scoringService.getScore(citingB, citationsExcludeSelf)).thenReturn(score(3.0));

        Map<String, Score> legacyCitations =
                scientificProductionService.calculateScientificImpactScore(cited, citingPublications, citations);
        Map<String, Score> cachedCitations =
                scientificProductionService.calculateScientificImpactScore(
                        cited,
                        citingPublications,
                        citations,
                        scientificProductionService.precomputeCitationBaseScores(citingPublications, citations)
                );
        assertEquals(legacyCitations.get("total").getAuthorScore(), cachedCitations.get("total").getAuthorScore(), 0.0001);
        assertEquals(legacyCitations.get("total").getScore(), cachedCitations.get("total").getScore(), 0.0001);

        Map<String, Score> legacyExcludeSelf =
                scientificProductionService.calculateScientificImpactScore(cited, citingPublications, citationsExcludeSelf);
        Map<String, Score> cachedExcludeSelf =
                scientificProductionService.calculateScientificImpactScore(
                        cited,
                        citingPublications,
                        citationsExcludeSelf,
                        scientificProductionService.precomputeCitationBaseScores(citingPublications, citationsExcludeSelf)
                );
        assertEquals(legacyExcludeSelf.get("total").getAuthorScore(), cachedExcludeSelf.get("total").getAuthorScore(), 0.0001);
        assertEquals(legacyExcludeSelf.get("total").getScore(), cachedExcludeSelf.get("total").getScore(), 0.0001);
    }

    // ---- INFO perspective-c out-of-list floor ("din afara listelor precizate ... va fi 1") ----

    @Test
    void outOfListCitingForumFloorsToOneCategoryDPoint() {
        // A citing publication whose venue the CS scorers can't rank (clean zero, no zeroReason) confers
        // 1 point — the standard's category-D / out-of-list rule for perspective c.
        Indicator citations = indicator("CITATIONS", "S/max(N-2, 1)");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "Cited", List.of("a1", "a2", "a3"));
        ScoringPublication citing = publication("cp-1", "f-unlisted", null, "ar", "ar", "Citing", List.of("b1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(citing, citations)).thenReturn(score(0.0));

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited, List.of(citing), citations);

        // S floored to 1, N=3 -> 1/max(3-2,1) = 1
        assertEquals(1.0, result.get("Citing").getAuthorScore(), 0.0001);
        assertEquals("D", result.get("Citing").getCoreRankingEquivalent());
        assertEquals("OUT_OF_LIST_D", result.get("Citing").getScoringSource());
        assertEquals(1.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void excludedVenueCitingForumStaysZeroDespiteTheFloor() {
        // "inclusiv reducerile sau excluderile" — a rule-based exclusion (predatory/standard-excluded
        // venue) is NOT an out-of-list venue and must not be floored.
        Indicator citations = indicator("CITATIONS", "S/max(N-2, 1)");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "Cited", List.of("a1"));
        ScoringPublication citing = publication("cp-1", "f-predatory", null, "ar", "ar", "Citing", List.of("b1"));
        Score excludedZero = score(0.0);
        excludedZero.getScoringInfo().put("zeroReason", "EXCLUDED_VENUE");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(citing, citations)).thenReturn(excludedZero);

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited, List.of(citing), citations);

        assertEquals(0.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void publicationsKindNeverGetsTheFloorInPerspectiveB() {
        // Perspective b counts only A*..C forums — the same getScore funnel serves the production path
        // (cited==citing), where the Publications kind must keep the clean zero.
        Indicator publicationsIndicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ScoringPublication pub = publication("p-1", "f-unlisted", null, "ar", "ar", "Own paper", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(pub, publicationsIndicator)).thenReturn(score(0.0));

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(
                List.of(pub), publicationsIndicator);

        assertEquals(0.0, result.get("total").getAuthorScore(), 0.0001);
        assertNull(result.get("Own paper")); // stays excluded, not scored
    }

    @Test
    void nonCsStrategyCitationsGetNoFloor() {
        Indicator citations = indicator("CITATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(citations, "AIS");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "Cited", List.of("a1"));
        ScoringPublication citing = publication("cp-1", "f-unlisted", null, "ar", "ar", "Citing", List.of("b1"));
        when(scoringFactoryService.getScoringService("AIS")).thenReturn(scoringService);
        when(scoringService.getScore(citing, citations)).thenReturn(score(0.0));

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited, List.of(citing), citations);

        assertEquals(0.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void flooredOutOfListCiterStillZeroedByTheFeeGate() {
        // An unlisted APC journal citer: floored to S=1, then the 2026 formula's feeJournal gate zeroes
        // it and the counterfactual probe stamps the reason.
        Indicator citations = indicator("CITATIONS", "feeJournal ? 0 : (S/max(N-2, 1))");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "Cited", List.of("a1"));
        ScoringPublication citing = publication("cp-1", "f-apc-unlisted", null, "ar", "ar", "Citing", List.of("b1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(citing, citations)).thenReturn(score(0.0));
        when(reportingLookupPort.isFeeJournal("f-apc-unlisted")).thenReturn(true);

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited, List.of(citing), citations);

        assertEquals(0.0, result.get("total").getAuthorScore(), 0.0001);
        assertEquals("FEE_JOURNAL", result.get("Citing").getScoringInfo().get("zeroReason"));
    }

    @Test
    void belowTopRankCiterIsExplainedByTheTopABCounterfactualProbe() {
        // A category-C citer (below the 2026 top A*/A/B cut, S<4) with feeJournal=false: the topAB probe
        // (force topAB=true, keep feeJournal at its real value) turns positive, proving the rank cut alone
        // caused the zero -> NOT_TOP_RANKED stamp, mirroring the FEE_JOURNAL probe above.
        Indicator citations = indicator("CITATIONS", "(topAB && !feeJournal) ? (S/max(N-2, 1)) : 0");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "Cited", List.of("a1"));
        ScoringPublication citing = publication("cp-1", "f-noapc", null, "ar", "ar", "Citing", List.of("b1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        Score cScore = score(3.0);
        cScore.setCoreRankingEquivalent("C");
        when(scoringService.getScore(citing, citations)).thenReturn(cScore);
        when(reportingLookupPort.isFeeJournal("f-noapc")).thenReturn(false);

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited, List.of(citing), citations);

        assertEquals(0.0, result.get("total").getAuthorScore(), 0.0001);
        assertEquals("NOT_TOP_RANKED", result.get("Citing").getScoringInfo().get("zeroReason"));
    }

    @Test
    void citingPaperFailingBothGatesStampsMultipleGates() {
        // A category-C, fee-gated citer fails both conditions: no single probe clears the zero, the
        // combined probe does — MULTIPLE_GATES tells the drilldown "several conditions", replacing
        // the old unstamped (bare formula-cutoff) outcome.
        Indicator citations = indicator("CITATIONS", "(topAB && !feeJournal) ? (S/max(N-2, 1)) : 0");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "Cited", List.of("a1"));
        ScoringPublication citing = publication("cp-1", "f-apc-c", null, "ar", "ar", "Citing", List.of("b1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        Score cScore = score(3.0);
        cScore.setCoreRankingEquivalent("C");
        when(scoringService.getScore(citing, citations)).thenReturn(cScore);
        when(reportingLookupPort.isFeeJournal("f-apc-c")).thenReturn(true);

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited, List.of(citing), citations);

        assertEquals(0.0, result.get("total").getAuthorScore(), 0.0001);
        assertEquals("MULTIPLE_GATES", result.get("Citing").getScoringInfo().get("zeroReason"));
    }

    @Test
    void universalSubtypeGateExcludesNonResearchPublicationsForAnyStrategy() {
        // Strategy intentionally non-CS ("AIS") to prove the gate lives in the shared
        // orchestrator and applies to every domain's scoring service, not just CS.
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "AIS");

        ScoringPublication article = publication("p-ar", null, null, "ar", "ar", "Real Article", List.of("a1"));
        ScoringPublication editorial = publication("p-ed", null, null, "ed", "ed", "An Editorial", List.of("a1"));

        when(scoringFactoryService.getScoringService("AIS")).thenReturn(scoringService);
        when(scoringService.getScore(article, indicator)).thenReturn(score(8.0));

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(
                List.of(article, editorial), indicator);

        // Editorial is gated before the scorer is consulted; only the article contributes.
        verify(scoringService, never()).getScore(editorial, indicator);
        assertEquals(8.0, result.get("Real Article").getAuthorScore(), 0.0001);
        assertEquals(null, result.get("An Editorial"));
        assertEquals(8.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void cachedBasePathRespectsFormulaUsingAuthorCountN() {
        Indicator indicator = indicator("CITATIONS", "S * N");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "cited-1", List.of("a1", "a2", "a3"));
        ScoringPublication citing = publication("cp-1", null, null, null, null, "cp-1", List.of("b1"));

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(citing, indicator)).thenReturn(score(2.0));

        Map<String, Score> precomputed = scientificProductionService.precomputeCitationBaseScores(List.of(citing), indicator);
        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited,
                List.of(citing),
                indicator,
                precomputed
        );

        assertEquals(6.0, result.get(citing.getTitle()).getAuthorScore(), 0.0001);
        assertEquals(6.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void cachedBaseScoresAreNotMutatedAcrossCalls() {
        Indicator indicator = indicator("CITATIONS", "S * N");
        ScoringPublication citedWithTwoAuthors = publication("cited-1", null, null, null, null, "cited-1", List.of("a1", "a2"));
        ScoringPublication citedWithFourAuthors = publication("cited-2", null, null, null, null, "cited-2", List.of("a1", "a2", "a3", "a4"));
        ScoringPublication citing = publication("cp-1", null, null, null, null, "cp-1", List.of("b1"));

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(citing, indicator)).thenReturn(score(2.0));

        Map<String, Score> precomputed = scientificProductionService.precomputeCitationBaseScores(List.of(citing), indicator);
        Score cachedBefore = precomputed.get("cp-1");
        assertNotNull(cachedBefore);
        assertEquals(0.0, cachedBefore.getAuthorScore(), 0.0001);

        scientificProductionService.calculateScientificImpactScore(citedWithTwoAuthors, List.of(citing), indicator, precomputed);
        scientificProductionService.calculateScientificImpactScore(citedWithFourAuthors, List.of(citing), indicator, precomputed);

        Score cachedAfter = precomputed.get("cp-1");
        assertNotNull(cachedAfter);
        assertEquals(0.0, cachedAfter.getAuthorScore(), 0.0001);
        assertEquals(2.0, cachedAfter.getScore(), 0.0001);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void publicationScoringUsesCoreConferenceMatchWhenMongoYearKeysAreStrings() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        ScoringPublication publication = publication(
                "pub-1", "forum-1", "2016-07-18", null, "cp",
                "Reusing Resource Coalitions for Efficient Scheduling on the Intercloud", List.of("a1", "a2", "a3"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings - 2016 16th IEEE/ACM International Symposium on Cluster, Cloud, and Grid Computing, CCGrid 2016");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("CCGRID");
        ranking.setName("IEEE International Symposium on Cluster, Cloud and Grid Computing");
        CoreConferenceRanking.YearlyRanking rank2017 = new CoreConferenceRanking.YearlyRanking();
        rank2017.setRank(CoreConferenceRanking.Rank.A);
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings((Map) Map.of(
                "2017", rank2017,
                "2023", rank2023
        ));
        when(lookupPort.getConferenceRankings("CCGRID")).thenReturn(List.of(ranking));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(8.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals(8.0, result.get(publication.getTitle()).getAuthorScore(), 0.0001);
        assertEquals("A", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals(2016, result.get(publication.getTitle()).getYear());
        assertEquals(8.0, result.get("total").getAuthorScore(), 0.0001);
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                conferenceScoringService.diagnoseConferenceMatch(forum.getPublicationName(), 2016);
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
        assertEquals("CCGRID", trace.resolvedAcronym());
    }

    @Test
    void publicationScoringUsesTrailingTitleCasedConferenceAcronym() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        ScoringPublication publication = publication(
                "pub-icnp-1", "forum-1", "2023-01-01", null, "cp",
                "Architecture for Confidential Digital Asset Transfer on Blockchain Through Obfuscation", List.of("a1"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Icnp");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICNP");
        ranking.setName("International Conference on Network Protocols");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(lookupPort.getConferenceRankings("ICNP")).thenReturn(List.of(ranking));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", result.get(publication.getTitle()).getScoringSource());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                conferenceScoringService.diagnoseConferenceMatch(forum.getPublicationName(), 2023);
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
        assertEquals("ICNP", trace.resolvedAcronym());
    }

    @Test
    void publicationScoringUsesNormalizedTitleCoreFallbackWhenAcronymIsMissing() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        ScoringPublication publication = publication(
                "pub-wopp-1", "forum-1", "2023-01-01", null, "cp",
                "Parallel Workloads in Workshop Proceedings", List.of("a1"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankingsByNormalizedTitle("proceedings of the international conference on parallel processing workshops"))
                .thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setId("WOPP-IEEE International Conference on Parallel Processing Workshops");
        ranking.setAcronym("WOPP");
        ranking.setName("IEEE International Conference on Parallel Processing Workshops");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(lookupPort.getConferenceRankingsByNormalizedTitle("parallel processing workshops")).thenReturn(List.of(ranking));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", result.get(publication.getTitle()).getScoringSource());
        assertEquals("TITLE", result.get(publication.getTitle()).getScoringInfo().get("coreLookupStrategy"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = conferenceScoringService.getLastTraceForTests();
        assertEquals(ComputerScienceConferenceScoringService.CoreLookupStrategy.TITLE, trace.resolvedLookupStrategy());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
    }

    @Test
    void publicationScoringHalvesWorkshopScoreWhenForumIsWorkshopOfParentConference() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        ScoringPublication publication = publication(
                "pub-percom-ws-1", "forum-1", "2023-01-01", null, "cp",
                "On the Use of Deep Neural Networks for Security Vulnerabilities Detection in Smart Contracts", List.of("a1"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("2023 IEEE International Conference on Pervasive Computing and Communications Workshops and Other Affiliated Events Percom Workshops 2023");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setId("PERCOM-IEEE International Conference on Pervasive Computing and Communications");
        ranking.setAcronym("PERCOM");
        ranking.setName("IEEE International Conference on Pervasive Computing and Communications");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.A);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(lookupPort.getConferenceRankings("PERCOM")).thenReturn(List.of(ranking));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        // Workshop downgrade: parent A → reported category B (points stay 4).
        assertEquals("B", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE(WS)", result.get(publication.getTitle()).getScoringSource());
        assertEquals(true, result.get(publication.getTitle()).getScoringInfo().get("workshopAdjusted"));
    }

    @Test
    void publicationScoringUsesDblpConferenceEvidenceForLncsChapter() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService =
                new ComputerScienceConferenceScoringService(lookupPort, dblpEvidenceRepository);

        ScoringPublication publication = publication(
                "pub-lncs-1", "forum-1", "2024-07-18", "ch", "ch",
                "A Chapter Hidden In LNCS", List.of("a1", "a2", "a3"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICSE");
        ranking.setName("International Conference on Software Engineering");
        CoreConferenceRanking.YearlyRanking rank2024 = new CoreConferenceRanking.YearlyRanking();
        rank2024.setRank(CoreConferenceRanking.Rank.A_STAR);
        ranking.setYearlyRankings(Map.of(2024, rank2024));
        when(lookupPort.getConferenceRankings("ICSE")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-lncs-1");
        evidence.setConferenceName("Proceedings of the International Conference on Software Engineering, ICSE 2024");
        when(dblpEvidenceRepository.findByPublicationId("pub-lncs-1")).thenReturn(Optional.of(evidence));
        when(scoringFactoryService.getScoringService("CS_CONFERENCE")).thenReturn(conferenceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS_CONFERENCE");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(12.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals(12.0, result.get(publication.getTitle()).getAuthorScore(), 0.0001);
        assertEquals("A_STAR", result.get(publication.getTitle()).getCoreRankingEquivalent());
        // Displayed year is the publication's own cover year (2024), not the resolved CORE ranking year — the
        // latter stays in scoringInfo.resolvedYear.
        assertEquals(2024, result.get(publication.getTitle()).getYear());
        assertEquals(2023, result.get(publication.getTitle()).getScoringInfo().get("resolvedYear"));
        assertEquals("DBLP+CORE", result.get(publication.getTitle()).getScoringSource());
        assertEquals("DBLP", result.get(publication.getTitle()).getScoringInfo().get("matchSource"));
        assertEquals(12.0, result.get("total").getAuthorScore(), 0.0001);
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = conferenceScoringService.getLastTraceForTests();
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
    }

    @Test
    void publicationScoringUsesDecoratedDblpAcronymEvidenceForLncsChapter() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService =
                new ComputerScienceConferenceScoringService(lookupPort, dblpEvidenceRepository);

        ScoringPublication publication = publication(
                "pub-aina-1", "forum-1", "2025-01-01", "ch", "ch",
                "AINA LNDECT Chapter", List.of("a1"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes on Data Engineering and Communications Technologies");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("AINA");
        ranking.setName("International Conference on Advanced Information Networking and Applications (was ICOIN)");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(lookupPort.getConferenceRankings("AINA")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-aina-1");
        evidence.setConferenceName("AINA (6)");
        when(dblpEvidenceRepository.findByPublicationId("pub-aina-1")).thenReturn(Optional.of(evidence));
        when(scoringFactoryService.getScoringService("CS_CONFERENCE")).thenReturn(conferenceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS_CONFERENCE");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals("DBLP+CORE", result.get(publication.getTitle()).getScoringSource());
        assertEquals("DBLP", result.get(publication.getTitle()).getScoringInfo().get("matchSource"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = conferenceScoringService.getLastTraceForTests();
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
        assertEquals("AINA (6)", trace.dblpConferenceTitle());
    }

    @Test
    void dblpEvidenceWithSeriesDoesNotDoubleTheAcronymAndStillResolvesCore() {
        // Regression (Munteanu STAC/AINA): sweep-written evidence carries series="conf/aina" AND a
        // conferenceName that already leads with the acronym ("AINA (6)"). The old composition produced
        // "AINA AINA (6)", which failed every confidence rung and demoted the paper to the LNCS C
        // fallback. It must resolve DBLP+CORE at the real AINA rank.
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceConferenceScoringService conferenceScoringService =
                new ComputerScienceConferenceScoringService(lookupPort, dblpEvidenceRepository);

        ScoringPublication publication = publication(
                "pub-aina-series", "forum-1", "2025-01-01", "ch", "ch",
                "Benchmarking STAC Ecosystem Server Backends", List.of("a1"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes on Data Engineering and Communications Technologies");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("AINA");
        ranking.setName("International Conference on Advanced Information Networking and Applications (was ICOIN)");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(lookupPort.getConferenceRankings("AINA")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-aina-series");
        evidence.setConferenceName("AINA (6)");
        evidence.setSeries("conf/aina");
        when(dblpEvidenceRepository.findByPublicationId("pub-aina-series")).thenReturn(Optional.of(evidence));
        when(scoringFactoryService.getScoringService("CS_CONFERENCE")).thenReturn(conferenceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS_CONFERENCE");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals("DBLP+CORE", result.get(publication.getTitle()).getScoringSource());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = conferenceScoringService.getLastTraceForTests();
        assertEquals("AINA (6)", trace.dblpConferenceTitle()); // acronym NOT doubled
    }

    @Test
    void productionScoreTop10SelectorSortsAndLimitsByAuthorScore() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        List<ScoringPublicationReadModel> publications = IntStream.range(0, 11)
                .mapToObj(i -> publication("p" + i, null, null, null, null, "Paper " + i, List.of("a1")))
                .map(ScoringPublicationReadModel.class::cast)
                .toList();

        for (int i = 0; i < 11; i++) {
            Score score = new Score();
            score.setScore(i == 0 ? 0.0 : (double) i);
            score.setAuthorScore(i == 0 ? 0.0 : (double) i);
            when(scoringService.getScore(publications.get(i), indicator)).thenReturn(score);
        }

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(publications, indicator);

        assertEquals(10, result.size() - 1);
        assertTrue(result.containsKey("Paper 10"));
        assertTrue(result.containsKey("Paper 1"));
        assertTrue(!result.containsKey("Paper 0"));
        assertEquals(55.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void precomputeCitationBaseScoresReturnsEmptyForGuardPathsAndSkipsDuplicateIds() {
        Indicator genericIndicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(genericIndicator, "GENERIC_COUNT");
        Indicator nullStrategyIndicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(nullStrategyIndicator, null);

        assertTrue(scientificProductionService.precomputeCitationBaseScores(null, genericIndicator).isEmpty());
        assertTrue(scientificProductionService.precomputeCitationBaseScores(List.of(), genericIndicator).isEmpty());
        assertTrue(scientificProductionService.precomputeCitationBaseScores(
                List.of(publication("p1", null, null, null, null, "P1", List.of("a1"))), null).isEmpty());
        assertTrue(scientificProductionService.precomputeCitationBaseScores(
                List.of(publication("p1", null, null, null, null, "P1", List.of("a1"))), nullStrategyIndicator).isEmpty());
        assertTrue(scientificProductionService.precomputeCitationBaseScores(
                List.of(publication("p1", null, null, null, null, "P1", List.of("a1"))), genericIndicator).isEmpty());

        Indicator indicator = indicator("CITATIONS", "S");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        ScoringPublication duplicateA = publication("dup", null, null, null, null, "A", List.of("a1"));
        ScoringPublication duplicateB = publication("dup", null, null, null, null, "B", List.of("a1"));
        ScoringPublication noId = new ScoringPublication(
                null,
                "eid-null",
                null,
                null,
                null,
                null,
                List.of("a1"),
                1,
                null,
                null,
                "NoId",
                0,
                Set.of()
        );
        Score baseScore = score(2.5);
        baseScore.setAuthorScore(9.0);
        when(scoringService.getScore(duplicateA, indicator)).thenReturn(baseScore);

        Map<String, Score> cached = scientificProductionService.precomputeCitationBaseScores(
                Arrays.asList(duplicateA, duplicateB, noId, null), indicator
        );

        assertEquals(1, cached.size());
        assertTrue(cached.containsKey("dup"));
        assertEquals(2.5, cached.get("dup").getScore(), 0.0001);
        assertEquals(9.0, cached.get("dup").getAuthorScore(), 0.0001);
        verify(scoringService, times(1)).getScore(duplicateA, indicator);
    }

    @Test
    void impactScoreAccumulatesTotalScoreAndAuthorScoreOnPositiveMatches() {
        Indicator indicator = indicator("CITATIONS", "S * N");
        ScoringPublication cited = publication("cited", null, null, null, null, "Cited", List.of("a1", "a2"));
        ScoringPublication citingA = publication("ca", null, null, null, null, "Citing A", List.of("b1"));
        ScoringPublication citingB = publication("cb", null, null, null, null, "Citing B", List.of("b2"));
        List<ScoringPublicationReadModel> citingPublications = List.of(citingA, citingB);

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        Score scoreA = new Score();
        scoreA.setScore(2.0);
        scoreA.setAuthorScore(0.0);
        Score scoreB = new Score();
        scoreB.setScore(3.0);
        scoreB.setAuthorScore(0.0);
        when(scoringService.getScore(citingA, indicator)).thenReturn(scoreA);
        when(scoringService.getScore(citingB, indicator)).thenReturn(scoreB);

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(cited, citingPublications, indicator);

        assertEquals(4.0, result.get("Citing A").getAuthorScore(), 0.0001);
        assertEquals(6.0, result.get("Citing B").getAuthorScore(), 0.0001);
        assertEquals(10.0, result.get("total").getAuthorScore(), 0.0001);
        assertEquals(5.0, result.get("total").getScore(), 0.0001);
    }

    @Test
    void impactScoreUsesCachedBaseByCitingPublicationIdWithoutServiceLookup() {
        Indicator indicator = indicator("CITATIONS", "S");
        ScoringPublication cited = publication("cited", null, null, null, null, "Cited", List.of("a1"));
        ScoringPublication citing = publication("cp-cache", null, null, null, null, "Cached", List.of("b1"));

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        Score cachedBase = new Score();
        cachedBase.setScore(7.0);
        cachedBase.setAuthorScore(13.0);
        // H52 slice 11c: details/errors/extra dropped; multiplier is the only
        // open-bag-replacement we still propagate.
        cachedBase.setMultiplier(5);
        cachedBase.setScoringInfo(new HashMap<>(Map.of("info", "cached")));
        Map<String, Score> cached = new HashMap<>();
        cached.put("cp-cache", cachedBase);

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited,
                List.of(citing),
                indicator,
                cached
        );

        verify(scoringService, never()).getScore(citing, indicator);
        assertEquals(7.0, result.get("Cached").getScore(), 0.0001);
        assertEquals(7.0, result.get("Cached").getAuthorScore(), 0.0001);
        assertEquals(7.0, result.get("total").getScore(), 0.0001);
        assertEquals(7.0, result.get("total").getAuthorScore(), 0.0001);
        assertEquals(13.0, cachedBase.getAuthorScore(), 0.0001);
        assertEquals(5, cachedBase.getMultiplier());
        assertEquals("cached", cachedBase.getScoringInfo().get("info"));
    }

    @Test
    void precomputeCitationBaseScoresCopiesAllRelevantScoreFieldsAndDetachesMaps() {
        Indicator indicator = indicator("CITATIONS", "S");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        ScoringPublication citing = publication("cp-copy", null, null, null, null, "Copy", List.of("a1"));

        Score base = new Score();
        base.setScore(9.0);
        base.setAuthorScore(4.5);
        base.setYear(2024);
        base.setCoreRankingEquivalent("A");
        base.setQuarter("Q1");
        base.setScoringSource("SOURCE");
        // H52 slice 11c: details/errors/extra dropped; the typed multiplier
        // is now the only open-bag-replacement field copyScore carries forward.
        base.setMultiplier(7);
        base.setScoringInfo(new HashMap<>(Map.of("k1", "v1")));
        when(scoringService.getScore(citing, indicator)).thenReturn(base);

        Map<String, Score> cached = scientificProductionService.precomputeCitationBaseScores(List.of(citing), indicator);
        Score copy = cached.get("cp-copy");

        assertNotNull(copy);
        assertNotSame(base, copy);
        assertEquals(9.0, copy.getScore(), 0.0001);
        assertEquals(4.5, copy.getAuthorScore(), 0.0001);
        assertEquals(2024, copy.getYear());
        assertEquals("A", copy.getCoreRankingEquivalent());
        assertEquals("Q1", copy.getQuarter());
        assertEquals("SOURCE", copy.getScoringSource());
        assertEquals(7, copy.getMultiplier());
        assertEquals("v1", copy.getScoringInfo().get("k1"));
        assertNotSame(base.getScoringInfo(), copy.getScoringInfo());

        copy.setAuthorScore(1.0);
        copy.getScoringInfo().put("k1", "mutated");

        assertEquals(4.5, base.getAuthorScore(), 0.0001);
        assertEquals("v1", base.getScoringInfo().get("k1"));
        assertFalse(base.getScoringInfo().containsValue("mutated"));
    }

    @Test
    void productionScoreWithSelectorAllKeepsInterResultAndSkipsZeroScores() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "ALL");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        ScoringPublicationReadModel p1 = publication("all-1", null, null, null, null, "All 1", List.of("a1"));
        ScoringPublicationReadModel p2 = publication("all-2", null, null, null, null, "All 2", List.of("a1"));
        ScoringPublicationReadModel p3 = publication("all-3", null, null, null, null, "All 3", List.of("a1"));

        Score s1 = new Score();
        s1.setScore(2.0);
        s1.setAuthorScore(2.0);
        Score s2 = new Score();
        s2.setScore(0.0);
        s2.setAuthorScore(0.0);
        Score s3 = new Score();
        s3.setScore(1.0);
        s3.setAuthorScore(1.0);

        when(scoringService.getScore(p1, indicator)).thenReturn(s1);
        when(scoringService.getScore(p2, indicator)).thenReturn(s2);
        when(scoringService.getScore(p3, indicator)).thenReturn(s3);

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(p1, p2, p3), indicator);

        assertTrue(result.containsKey("All 1"));
        assertFalse(result.containsKey("All 2"));
        assertTrue(result.containsKey("All 3"));
        assertEquals(3.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void productionScoreTop10SelectorWithAtMostTenEntriesKeepsAllPositiveWithoutSortingBranch() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        List<ScoringPublicationReadModel> publications = IntStream.rangeClosed(1, 3)
                .mapToObj(i -> publication("t" + i, null, null, null, null, "Top " + i, List.of("a1")))
                .map(ScoringPublicationReadModel.class::cast)
                .toList();

        for (int i = 0; i < publications.size(); i++) {
            Score score = new Score();
            score.setScore(i + 1.0);
            score.setAuthorScore(i + 1.0);
            when(scoringService.getScore(publications.get(i), indicator)).thenReturn(score);
        }

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(publications, indicator);

        assertEquals(3, result.size() - 1);
        assertEquals(6.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void productionScoreTop10SelectorSortsAndKeepsBestTenWhenMoreThanTenPublications() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        List<ScoringPublicationReadModel> publications = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> publication("mx-" + i, null, null, null, null, "Max " + i, List.of("a1")))
                .map(ScoringPublicationReadModel.class::cast)
                .toList();

        for (int i = 0; i < publications.size(); i++) {
            Score score = new Score();
            score.setScore(i + 1.0);
            score.setAuthorScore(i + 1.0);
            when(scoringService.getScore(publications.get(i), indicator)).thenReturn(score);
        }

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(publications, indicator);

        assertEquals(10, result.size() - 1);
        assertEquals(75.0, result.get("total").getAuthorScore(), 0.0001); // 12+...+3
        assertTrue(result.containsKey("Max 12"));
        assertFalse(result.containsKey("Max 1"));
    }

    @Test
    void precomputeCitationBaseScoresGuardPathsDoNotTouchFactoryOrScoringService() {
        Indicator genericIndicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(genericIndicator, "GENERIC_COUNT");
        Indicator nullStrategyIndicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(nullStrategyIndicator, null);
        List<ScoringPublicationReadModel> nonEmpty = List.of(
                publication("guard", null, null, null, null, "Guard", List.of("a1"))
        );

        scientificProductionService.precomputeCitationBaseScores(null, genericIndicator);
        scientificProductionService.precomputeCitationBaseScores(List.of(), genericIndicator);
        scientificProductionService.precomputeCitationBaseScores(nonEmpty, null);
        scientificProductionService.precomputeCitationBaseScores(nonEmpty, nullStrategyIndicator);
        scientificProductionService.precomputeCitationBaseScores(nonEmpty, genericIndicator);

        verifyNoInteractions(scoringFactoryService);
        verifyNoInteractions(scoringService);
    }

    @Test
    void precomputeCitationBaseScoresDeduplicatesIdsAndReturnsIndependentCopies() {
        Indicator indicator = indicator("CITATIONS", "S");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        ScoringPublicationReadModel p1 = publication("dup", null, null, null, null, "Dup A", List.of("a1"));
        ScoringPublicationReadModel p2 = publication("dup", null, null, null, null, "Dup B", List.of("a1"));
        Score base = score(2.0);
        when(scoringService.getScore(p1, indicator)).thenReturn(base);

        Map<String, Score> cached = scientificProductionService.precomputeCitationBaseScores(List.of(p1, p2), indicator);

        assertEquals(1, cached.size());
        assertEquals(2.0, cached.get("dup").getScore(), 0.0001);
        assertNotSame(base, cached.get("dup"));
        verify(scoringService, times(1)).getScore(p1, indicator);
    }

    @Test
    void nanosToMillisUsesFloorAndNeverReturnsNegative() throws Exception {
        Method m = ScientificProductionService.class.getDeclaredMethod("nanosToMillis", long.class);
        m.setAccessible(true);

        assertEquals(0L, m.invoke(scientificProductionService, -1L));
        assertEquals(0L, m.invoke(scientificProductionService, 999_999L));
        assertEquals(1L, m.invoke(scientificProductionService, 1_000_000L));
        assertEquals(2L, m.invoke(scientificProductionService, 2_999_999L));
    }

    @Test
    void reflectivePrivateHelpersCoverLegacyCitationAndNullCopyBranches() throws Exception {
        Indicator indicator = indicator("CITATIONS", "S");
        ScoringPublication cited = publication("c-1", null, null, null, null, "Cited", List.of("a1"));
        ScoringPublication citing = publication("x-1", null, null, null, null, "Citing", List.of("b1"));
        when(scoringService.getScore(citing, indicator)).thenReturn(score(3.0));

        Method legacyCitation = ScientificProductionService.class.getDeclaredMethod(
                "calculateCitationScore",
                ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel.class,
                ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel.class,
                Indicator.class,
                ScoringService.class
        );
        legacyCitation.setAccessible(true);
        Score legacy = (Score) legacyCitation.invoke(scientificProductionService, cited, citing, indicator, scoringService);
        assertEquals(3.0, legacy.getScore(), 0.0001);

        Method copyScore = ScientificProductionService.class.getDeclaredMethod("copyScore", Score.class);
        copyScore.setAccessible(true);
        Score copiedFromNull = (Score) copyScore.invoke(scientificProductionService, new Object[]{null});
        assertNotNull(copiedFromNull);
        assertEquals(0.0, copiedFromNull.getScore(), 0.0001);
    }

    private Indicator indicator(String typeName, String formula) {
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, typeName);
        indicator.setFormula(formula);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        return indicator;
    }

    private ScoringPublication publication(String id,
                                           String forumId,
                                           String coverDate,
                                           String subtype,
                                           String scopusSubtype,
                                           String title,
                                           List<String> authors) {
        return new ScoringPublication(
                id,
                "eid-" + id,
                forumId,
                coverDate,
                subtype,
                scopusSubtype,
                authors,
                authors.size(),
                "10.1000/" + id,
                null,
                title,
                0,
                Set.of()
        );
    }

    private Score score(double value) {
        Score score = new Score();
        score.setScore(value);
        score.setAuthorScore(0.0);
        return score;
    }

    // ── FEAA 2026: TopNPerForumYear selector (max 1/journal-year, Core/Info exempt, then top N) ──

    @Test
    void topNPerForumYearCapsPerJournalYearWithCoreInfoExemption() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "ECONOMICS_JOURNAL_AIS");
        indicator.setSelectorSpec(new ro.uvt.pokedex.core.model.reporting.scoring.Selector.TopNPerForumYear(10, 1, 8));

        // Same journal, same year: two Social-Science articles (M=3, capped at 1) + two Core articles
        // (M=10, exempt). Different year on the same journal: its own bucket.
        ScoringPublication socialA = publication("p-s1", "f-j1", "2023-05-01", "ar", "ar", "Social A", List.of("a1"));
        ScoringPublication socialB = publication("p-s2", "f-j1", "2023-09-01", "ar", "ar", "Social B", List.of("a1"));
        ScoringPublication socialOtherYear = publication("p-s3", "f-j1", "2022-01-01", "ar", "ar", "Social 2022", List.of("a1"));
        ScoringPublication coreA = publication("p-c1", "f-j1", "2023-02-01", "ar", "ar", "Core A", List.of("a1"));
        ScoringPublication coreB = publication("p-c2", "f-j1", "2023-08-01", "ar", "ar", "Core B", List.of("a1"));
        when(scoringFactoryService.getScoringService("ECONOMICS_JOURNAL_AIS")).thenReturn(scoringService);
        when(scoringService.getScore(socialA, indicator)).thenReturn(scoreWithYearAndMultiplier(2.0, 2023, 3));
        when(scoringService.getScore(socialB, indicator)).thenReturn(scoreWithYearAndMultiplier(1.5, 2023, 3));
        when(scoringService.getScore(socialOtherYear, indicator)).thenReturn(scoreWithYearAndMultiplier(1.0, 2022, 3));
        when(scoringService.getScore(coreA, indicator)).thenReturn(scoreWithYearAndMultiplier(3.0, 2023, 10));
        when(scoringService.getScore(coreB, indicator)).thenReturn(scoreWithYearAndMultiplier(2.5, 2023, 10));

        ScientificProductionService.ScoredProductionResult result =
                scientificProductionService.calculateScientificProductionScoreDetailed(
                        List.of(socialA, socialB, socialOtherYear, coreA, coreB), indicator);

        // Kept: both Core (exempt), Social A (best of the 2023 bucket), Social 2022 (own bucket).
        assertEquals(3.0 + 2.5 + 2.0 + 1.0, result.scores().get("total").getAuthorScore(), 0.0001);
        assertTrue(result.scores().containsKey("Core A"));
        assertTrue(result.scores().containsKey("Core B"));
        assertTrue(result.scores().containsKey("Social A"));
        assertTrue(result.scores().containsKey("Social 2022"));
        // Social B lost the 2023 bucket to the higher-scoring Social A.
        assertEquals("OVER_PER_FORUM_CAP",
                result.excluded().get("Social B").getScoringInfo().get("zeroReason"));
    }

    @Test
    void topNPerForumYearAppliesGlobalTopNAfterTheJournalCap() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "ECONOMICS_JOURNAL_AIS");
        indicator.setSelectorSpec(new ro.uvt.pokedex.core.model.reporting.scoring.Selector.TopNPerForumYear(2, 1, 8));

        ScoringPublication a = publication("p-a", "f-1", "2023-01-01", "ar", "ar", "A", List.of("a1"));
        ScoringPublication b = publication("p-b", "f-2", "2023-01-01", "ar", "ar", "B", List.of("a1"));
        ScoringPublication c = publication("p-c", "f-3", "2023-01-01", "ar", "ar", "C", List.of("a1"));
        when(scoringFactoryService.getScoringService("ECONOMICS_JOURNAL_AIS")).thenReturn(scoringService);
        when(scoringService.getScore(a, indicator)).thenReturn(scoreWithYearAndMultiplier(3.0, 2023, 3));
        when(scoringService.getScore(b, indicator)).thenReturn(scoreWithYearAndMultiplier(2.0, 2023, 3));
        when(scoringService.getScore(c, indicator)).thenReturn(scoreWithYearAndMultiplier(1.0, 2023, 3));

        ScientificProductionService.ScoredProductionResult result =
                scientificProductionService.calculateScientificProductionScoreDetailed(
                        List.of(a, b, c), indicator);

        assertEquals(5.0, result.scores().get("total").getAuthorScore(), 0.0001); // top 2 of 3
        assertEquals("NOT_IN_TOP_N", result.excluded().get("C").getScoringInfo().get("zeroReason"));
    }

    private Score scoreWithYearAndMultiplier(double authorAndBase, int year, int multiplier) {
        Score score = new Score();
        score.setScore(authorAndBase);
        score.setAuthorScore(0.0);
        score.setYear(year);
        score.setMultiplier(multiplier);
        return score;
    }

    // ── FEAA point 6: N_ro formula variable (RO-affiliated author count) ──

    @Test
    void nRoFormulaBindsCountryRestrictedAuthorCountLazily() {
        // Formula references N_ro → the helper is consulted; N stays the full count alongside.
        Indicator indicator = indicator("PUBLICATIONS", "S * (1 - (N_ro-1) * 0.1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        ScoringPublication paper = publication("p-1", "f-1", "2023-01-01", "ar", "ar",
                "Paper", List.of("a1", "a2", "a3", "a4"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(paper, indicator)).thenReturn(score(2.0));
        when(publicationCountryAuthorCountService.authorCountForCountry(paper, "Romania")).thenReturn(2);

        Map<String, Score> result = scientificProductionService
                .calculateScientificProductionScore(List.of(paper), indicator);

        // 2.0 * (1 - (2-1)*0.1) = 1.8 — the RO count (2), not the total (4, which would give 1.4).
        assertEquals(1.8, result.get("Paper").getAuthorScore(), 0.0001);
    }

    @Test
    void nonNRoFormulaNeverTouchesTheAffiliationHelper() {
        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2,1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        ScoringPublication paper = publication("p-2", "f-1", "2023-01-01", "ar", "ar",
                "Other", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(paper, indicator)).thenReturn(score(2.0));

        scientificProductionService.calculateScientificProductionScore(List.of(paper), indicator);

        verify(publicationCountryAuthorCountService, never())
                .authorCountForCountry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ── H95 S5: FEAA 2026 Punctul 5 sub-condition + Punctul 8 book count formulas ──

    @Test
    void feaaBooksCountFormulaCountsAuthoredBooksOnly() {
        // Punctul 8: "publicarea cel puțin a unei cărți de specialitate" — docType 'bk' with a positive
        // book coefficient counts 1; chapters ('ch') never do, whatever they score.
        Indicator indicator = indicator("PUBLICATIONS", "docType == \"bk\" && S > 0 ? 1 : 0");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "FEAA_BOOK");
        ScoringPublication book = publication("p-bk", "f-1", "2021-01-01", "bk", "bk",
                "Authored Book", List.of("a1"));
        ScoringPublication chapter = publication("p-ch", "f-1", "2022-01-01", "ch", "ch",
                "Chapter", List.of("a1"));
        when(scoringFactoryService.getScoringService("FEAA_BOOK")).thenReturn(scoringService);
        when(scoringService.getScore(book, indicator)).thenReturn(score(0.1));
        when(scoringService.getScore(chapter, indicator)).thenReturn(score(0.3));

        Map<String, Score> result = scientificProductionService
                .calculateScientificProductionScore(List.of(book, chapter), indicator);

        assertEquals(1.0, result.get("Authored Book").getAuthorScore(), 0.0001);
        assertEquals(1.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void feaaCoreInfoAisGateRequiresBothMultiplierAndAisAboveThreshold() {
        // Punctul 5 prof sub-condition: "din cele 3 articole Core/Info, minim unul cu AIS > 0,15" —
        // counts only Core (M=10) / Info (M=8) articles whose AIS clears 0.15.
        Indicator indicator = indicator("PUBLICATIONS", "(M == 10 || M == 8) && S > 0.15 ? 1 : 0");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "ECONOMICS_JOURNAL_AIS");
        ScoringPublication core = publication("p-core", "f-1", "2023-01-01", "ar", "ar",
                "Core High AIS", List.of("a1"));
        ScoringPublication residual = publication("p-res", "f-2", "2023-01-01", "ar", "ar",
                "Residual High AIS", List.of("a1"));
        ScoringPublication coreLow = publication("p-low", "f-3", "2023-01-01", "ar", "ar",
                "Core Low AIS", List.of("a1"));
        when(scoringFactoryService.getScoringService("ECONOMICS_JOURNAL_AIS")).thenReturn(scoringService);
        Score coreScore = score(0.4);
        coreScore.setMultiplier(10);
        Score residualScore = score(0.4);
        residualScore.setMultiplier(3);
        Score coreLowScore = score(0.1);
        coreLowScore.setMultiplier(10);
        when(scoringService.getScore(core, indicator)).thenReturn(coreScore);
        when(scoringService.getScore(residual, indicator)).thenReturn(residualScore);
        when(scoringService.getScore(coreLow, indicator)).thenReturn(coreLowScore);

        Map<String, Score> result = scientificProductionService
                .calculateScientificProductionScore(List.of(core, residual, coreLow), indicator);

        assertEquals(1.0, result.get("total").getAuthorScore(), 0.0001); // only the Core M=10 AIS 0.4
    }

    // ── APC visibility: feeJournal venue fact + named MULTIPLE_GATES causes ──

    @Test
    void feeJournalVenueFactIsStampedEvenWhenTheItemScores() {
        // FEAA does not exclude APC journals — the badge is a venue FACT, independent of gating.
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        ScoringPublication paper = publication("p-apc", "f-mdpi", "2022-01-01", "ar", "ar",
                "Electronics Paper", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(paper, indicator)).thenReturn(score(4.0));
        when(reportingLookupPort.isFeeJournal("f-mdpi")).thenReturn(true);

        Map<String, Score> result = scientificProductionService
                .calculateScientificProductionScore(List.of(paper), indicator);

        assertEquals(4.0, result.get("Electronics Paper").getAuthorScore(), 0.0001);
        assertEquals(Boolean.TRUE, result.get("Electronics Paper").getScoringInfo().get("feeJournal"));
    }

    @Test
    void multipleGatesZeroNamesItsCauses() {
        // The Info "top A*/A" shape: S >= 8 AND !feeJournal — an APC paper below the bar fails BOTH,
        // and the pill must be able to say so instead of the vague "mai multe condiții".
        Indicator indicator = indicator("PUBLICATIONS", "(S >= 8 && !feeJournal) ? S : 0");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        ScoringPublication paper = publication("p-apc2", "f-mdpi", "2022-01-01", "ar", "ar",
                "Gated Paper", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(paper, indicator)).thenReturn(score(4.0));
        when(reportingLookupPort.isFeeJournal("f-mdpi")).thenReturn(true);

        ScientificProductionService.ScoredProductionResult result =
                scientificProductionService.calculateScientificProductionScoreDetailed(
                        List.of(paper), indicator);

        Score gated = result.scores().containsKey("Gated Paper")
                ? result.scores().get("Gated Paper") : result.excluded().get("Gated Paper");
        assertEquals("MULTIPLE_GATES", gated.getScoringInfo().get("zeroReason"));
        assertEquals("FEE_JOURNAL,SCORE_BELOW_FORMULA_THRESHOLD",
                gated.getScoringInfo().get("gateCauses"));
        assertEquals(Boolean.TRUE, gated.getScoringInfo().get("feeJournal"));
    }

    // ── S2: Poz formula variable — per-position item scores and totals ──

    @Test
    @SuppressWarnings("unchecked")
    void pozFormulaStampsPerPositionDivergencesOnItemsAndTotal() {
        // The FV Info 2016 ruling: category-D conference points (S=1) count canonically and for
        // Asist/Lect; they are cut only when the target position is CONF_UNIV/PROF_UNIV.
        Indicator indicator = indicator("PUBLICATIONS",
                "(Poz == 'CONF_UNIV' || Poz == 'PROF_UNIV') ? (S > 1 ? S/max(N-2,1) : 0) : S/max(N-2,1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        ScoringPublication rankedB = publication("p-b", "f-b", "2022-01-01", "ar", "ar",
                "Ranked B Paper", List.of("a1"));
        ScoringPublication rankedD = publication("p-d", "f-d", "2021-01-01", "ar", "ar",
                "Ranked D Paper", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(rankedB, indicator)).thenReturn(score(4.0));
        when(scoringService.getScore(rankedD, indicator)).thenReturn(score(1.0));

        Map<String, Score> result = scientificProductionService
                .calculateScientificProductionScore(List.of(rankedB, rankedD), indicator);

        // Canonical (Poz = "") counts D: total 4 + 1 = 5.
        assertEquals(5.0, result.get("total").getAuthorScore(), 0.0001);
        // The D item diverges ONLY for Conf/Prof (cut to 0); Asist/Lect equal canonical → absent.
        Map<String, Double> dByPosition = (Map<String, Double>)
                result.get("Ranked D Paper").getScoringInfo()
                        .get(ScientificProductionService.AUTHOR_SCORE_BY_POSITION);
        assertEquals(0.0, dByPosition.get("CONF_UNIV"), 0.0001);
        assertEquals(0.0, dByPosition.get("PROF_UNIV"), 0.0001);
        org.junit.jupiter.api.Assertions.assertFalse(dByPosition.containsKey("ASIST_UNIV"));
        org.junit.jupiter.api.Assertions.assertFalse(dByPosition.containsKey("LECT_UNIV"));
        // The B item never diverges → no map at all.
        org.junit.jupiter.api.Assertions.assertNull(result.get("Ranked B Paper").getScoringInfo()
                .get(ScientificProductionService.AUTHOR_SCORE_BY_POSITION));
        // The total carries only the diverging positions: Conf/Prof = 4 (D cut).
        Map<String, Double> totalByPosition = (Map<String, Double>)
                result.get("total").getScoringInfo()
                        .get(ScientificProductionService.AUTHOR_SCORE_BY_POSITION);
        assertEquals(4.0, totalByPosition.get("CONF_UNIV"), 0.0001);
        assertEquals(4.0, totalByPosition.get("PROF_UNIV"), 0.0001);
        org.junit.jupiter.api.Assertions.assertFalse(totalByPosition.containsKey("ASIST_UNIV"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mathLectorFormulaDropsTheAuthorDivisorForLectorOnly() {
        // "Se consideră nᵢ = 1 pentru lector" (FV Matematică, nota fișei din Anexa 1): the S sum
        // divides sᵢ by the author count everywhere EXCEPT the Lector position, where each article
        // counts its full sᵢ. Canonical (Poz = "") keeps the divisor, so stored scores don't move.
        Indicator indicator = indicator("PUBLICATIONS", "S > 0.5 ? (Poz == \"LECT_UNIV\" ? S : S/N) : 0");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "RIS");
        ScoringPublication coauthored = publication("p-ris", "f-ris", "2022-01-01", "ar", "ar",
                "Coauthored Paper", List.of("a1", "a2", "a3", "a4"));
        when(scoringFactoryService.getScoringService("RIS")).thenReturn(scoringService);
        when(scoringService.getScore(coauthored, indicator)).thenReturn(score(2.0));

        Map<String, Score> result = scientificProductionService
                .calculateScientificProductionScore(List.of(coauthored), indicator);

        assertEquals(0.5, result.get("total").getAuthorScore(), 0.0001); // canonical: 2.0 / 4 authors
        Map<String, Double> byPosition = (Map<String, Double>)
                result.get("Coauthored Paper").getScoringInfo()
                        .get(ScientificProductionService.AUTHOR_SCORE_BY_POSITION);
        assertEquals(2.0, byPosition.get("LECT_UNIV"), 0.0001); // undivided for Lector
        // Every other position matches canonical, so nothing else is stamped.
        org.junit.jupiter.api.Assertions.assertFalse(byPosition.containsKey("CONF_UNIV"));
        org.junit.jupiter.api.Assertions.assertFalse(byPosition.containsKey("PROF_UNIV"));
        org.junit.jupiter.api.Assertions.assertFalse(byPosition.containsKey("ASIST_UNIV"));
    }

    @Test
    void nonPozFormulaStampsNothing() {
        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2,1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        ScoringPublication paper = publication("p-1", "f-1", "2022-01-01", "ar", "ar",
                "A Paper", List.of("a1"));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(paper, indicator)).thenReturn(score(4.0));

        Map<String, Score> result = scientificProductionService
                .calculateScientificProductionScore(List.of(paper), indicator);

        org.junit.jupiter.api.Assertions.assertNull(result.get("A Paper").getScoringInfo()
                .get(ScientificProductionService.AUTHOR_SCORE_BY_POSITION));
        org.junit.jupiter.api.Assertions.assertNull(result.get("total").getScoringInfo()
                .get(ScientificProductionService.AUTHOR_SCORE_BY_POSITION));
    }
}
