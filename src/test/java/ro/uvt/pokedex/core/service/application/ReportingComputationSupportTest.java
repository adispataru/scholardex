package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportingComputationSupportTest {

    @Test
    void calculatePublicationScoreSupportsMainAndCoauthorFilters() {
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);

        ScholardexAuthorView a1 = new ScholardexAuthorView();
        a1.setId("a1");
        ScholardexAuthorView a2 = new ScholardexAuthorView();
        a2.setId("a2");

        ScholardexPublicationView pMain = new ScholardexPublicationView();
        pMain.setId("p-main");
        pMain.setAuthors(List.of("a1", "a2"));

        ScholardexPublicationView pCo = new ScholardexPublicationView();
        pCo.setId("p-co");
        pCo.setAuthors(List.of("x", "a1"));

        Indicator main = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(main, "PUBLICATIONS_MAIN_AUTHOR");
        Indicator co = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(co, "PUBLICATIONS_COAUTHOR");

        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(main)))
                .thenReturn(Map.of("total", totalScore(4.0)));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(co)))
                .thenReturn(Map.of("total", totalScore(2.0)));

        double mainScore = ReportingComputationSupport.calculatePublicationScore(
                main, List.of(a1, a2), List.of(pMain, pCo), scientificProductionService);
        double coScore = ReportingComputationSupport.calculatePublicationScore(
                co, List.of(a1, a2), List.of(pMain, pCo), scientificProductionService);

        assertEquals(4.0, mainScore);
        assertEquals(2.0, coScore);
    }

    @Test
    void isCitationsOutputHandlesNullsAndBothCitationTypes() {
        // H52 slice 11e: the {@code ReportingComputationSupport.isCitationIndicator}
        // wrapper was inlined; this test now exercises {@link Indicator#isCitationsOutput()}
        // directly, which is the same behavior.
        Indicator nullOutput = new Indicator();
        Indicator plain = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(plain, "PUBLICATIONS");
        Indicator citations = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(citations, "CITATIONS");
        Indicator citationsExcludeSelf = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(citationsExcludeSelf, "CITATIONS_EXCLUDE_SELF");

        assertFalse(nullOutput.isCitationsOutput());
        assertFalse(plain.isCitationsOutput());
        assertTrue(citations.isCitationsOutput());
        assertTrue(citationsExcludeSelf.isCitationsOutput());
    }

    @Test
    void applyFinalSelectorKeepsOnlyTop10AndRebuildsTotals() {
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");

        Map<String, Score> scoreMap = new LinkedHashMap<>();
        for (int i = 1; i <= 12; i++) {
            Score score = new Score();
            score.setAuthorScore(i);
            score.setScore(i);
            scoreMap.put("p" + i, score);
        }
        scoreMap.put("total", totalScore(0));

        Map<String, Map<String, Score>> nested = new LinkedHashMap<>();
        nested.put("root", scoreMap);

        ReportingComputationSupport.applyFinalSelector(indicator, nested);

        Map<String, Score> pruned = nested.get("root");
        assertEquals(11, pruned.size());
        assertFalse(pruned.containsKey("p1"));
        assertFalse(pruned.containsKey("p2"));
        assertTrue(pruned.containsKey("p12"));
        assertEquals(75.0, pruned.get("total").getAuthorScore());
    }

    @Test
    void applyFinalSelectorDoesNothingWhenSelectorIsNotTop10() {
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "ALL");
        Map<String, Map<String, Score>> nested = new LinkedHashMap<>();
        Map<String, Score> one = new LinkedHashMap<>();
        one.put("p1", totalScore(1));
        one.put("total", totalScore(1));
        nested.put("root", one);

        ReportingComputationSupport.applyFinalSelector(indicator, nested);

        assertEquals(2, nested.get("root").size());
        assertTrue(nested.get("root").containsKey("p1"));
    }

    @Test
    void applyFinalSelectorDoesNothingWhenSelectorIsNull() {
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, null);
        Map<String, Map<String, Score>> nested = new LinkedHashMap<>();
        Map<String, Score> one = new LinkedHashMap<>();
        one.put("p1", totalScore(1));
        one.put("total", totalScore(1));
        nested.put("root", one);

        ReportingComputationSupport.applyFinalSelector(indicator, nested);

        assertEquals(2, nested.get("root").size());
        assertEquals(1.0, nested.get("root").get("total").getScore(), 0.0001);
    }

    @Test
    void applyFinalSelectorHandlesDuplicateTitlesAcrossBucketsWithoutDoubleKeeping() {
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");

        Map<String, Score> b1 = new LinkedHashMap<>();
        b1.put("shared", totalScore(10));
        b1.put("other-a", totalScore(8));
        b1.put("total", totalScore(18));
        Map<String, Score> b2 = new LinkedHashMap<>();
        b2.put("shared", totalScore(9));
        b2.put("other-b", totalScore(7));
        b2.put("total", totalScore(16));
        Map<String, Map<String, Score>> nested = new LinkedHashMap<>();
        nested.put("b1", b1);
        nested.put("b2", b2);

        ReportingComputationSupport.applyFinalSelector(indicator, nested);

        int keptShared = 0;
        if (nested.get("b1").containsKey("shared")) {
            keptShared++;
        }
        if (nested.get("b2").containsKey("shared")) {
            keptShared++;
        }
        assertEquals(1, keptShared);
        assertTrue(nested.get("b1").containsKey("total"));
        assertTrue(nested.get("b2").containsKey("total"));
        assertTrue(nested.get("b1").get("total").getScore() >= 0);
        assertTrue(nested.get("b2").get("total").getScore() >= 0);
    }

    @Test
    void calculatePublicationScoreMainAndCoauthorRespectFirstAuthorConstraint() {
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);

        ScholardexAuthorView a1 = new ScholardexAuthorView();
        a1.setId("a1");

        ScholardexPublicationView firstAuthorMatches = publication("p-main", List.of("a1", "x"));
        ScholardexPublicationView coauthorOnly = publication("p-co", List.of("x", "a1"));
        ScholardexPublicationView foreignFirst = publication("p-foreign", List.of("z", "a1"));
        ScholardexPublicationView noAuthors = publication("p-empty", List.of());
        ScholardexPublicationView nullAuthors = publication("p-null", null);

        Indicator main = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(main, "PUBLICATIONS_MAIN_AUTHOR");
        Indicator co = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(co, "PUBLICATIONS_COAUTHOR");

        doAnswer(invocation -> Map.of("total", totalScore(((List<ScoringPublicationReadModel>) invocation.getArgument(0)).size())))
                .when(scientificProductionService).calculateScientificProductionScore(anyList(), eq(main));
        doAnswer(invocation -> Map.of("total", totalScore(((List<ScoringPublicationReadModel>) invocation.getArgument(0)).size())))
                .when(scientificProductionService).calculateScientificProductionScore(anyList(), eq(co));

        List<ScholardexPublicationView> publications = List.of(
                firstAuthorMatches, coauthorOnly, foreignFirst, noAuthors, nullAuthors
        );

        double mainScore = ReportingComputationSupport.calculatePublicationScore(
                main, List.of(a1), publications, scientificProductionService
        );
        double coScore = ReportingComputationSupport.calculatePublicationScore(
                co, List.of(a1), publications, scientificProductionService
        );

        assertEquals(1.0, mainScore);
        assertEquals(4.0, coScore);
    }

    @Test
    @SuppressWarnings("unchecked")
    void calculatePublicationScoreFirstOrCorrespondingIncludesCorrespondingNonFirst() {
        // H63: FIRST_OR_CORRESPONDING keeps a pub if the candidate is the first author OR a corresponding author.
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);
        ScholardexAuthorView a1 = new ScholardexAuthorView();
        a1.setId("a1");

        ScholardexPublicationView firstAuthor = publication("p-first", List.of("a1", "x"));        // first → in
        ScholardexPublicationView correspondingNotFirst = publication("p-corr", List.of("x", "a1"));
        correspondingNotFirst.setCorrespondingAuthorIds(List.of("a1"));                            // corresponding → in
        ScholardexPublicationView coauthorNoCorresponding = publication("p-co", List.of("x", "a1")); // neither → out (fallback)
        ScholardexPublicationView foreign = publication("p-foreign", List.of("z", "y"));
        foreign.setCorrespondingAuthorIds(List.of("w"));                                           // neither → out

        Indicator firstOrCorr = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(firstOrCorr, "PUBLICATIONS_FIRST_OR_CORRESPONDING");
        Indicator main = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(main, "PUBLICATIONS_MAIN_AUTHOR");

        doAnswer(invocation -> Map.of("total", totalScore(((List<ScoringPublicationReadModel>) invocation.getArgument(0)).size())))
                .when(scientificProductionService).calculateScientificProductionScore(anyList(), eq(firstOrCorr));
        doAnswer(invocation -> Map.of("total", totalScore(((List<ScoringPublicationReadModel>) invocation.getArgument(0)).size())))
                .when(scientificProductionService).calculateScientificProductionScore(anyList(), eq(main));

        List<ScholardexPublicationView> pubs = List.of(firstAuthor, correspondingNotFirst, coauthorNoCorresponding, foreign);

        double firstOrCorrScore = ReportingComputationSupport.calculatePublicationScore(
                firstOrCorr, List.of(a1), pubs, scientificProductionService);
        double mainScore = ReportingComputationSupport.calculatePublicationScore(
                main, List.of(a1), pubs, scientificProductionService);

        assertEquals(2.0, firstOrCorrScore); // first-author + corresponding-non-first
        assertEquals(1.0, mainScore);        // first-author only — the corresponding pub is NOT counted by MAIN
    }

    @Test
    void calculatePublicationScoreDoesNotTreatMissingFirstAuthorAsEmptyStringMatch() {
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);
        ScholardexAuthorView blankAuthor = new ScholardexAuthorView();
        blankAuthor.setId("");

        ScholardexPublicationView noAuthors = publication("p-empty", List.of());
        Indicator main = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(main, "PUBLICATIONS_MAIN_AUTHOR");

        doAnswer(invocation -> Map.of("total", totalScore(((List<ScoringPublicationReadModel>) invocation.getArgument(0)).size())))
                .when(scientificProductionService).calculateScientificProductionScore(anyList(), eq(main));

        double score = ReportingComputationSupport.calculatePublicationScore(
                main, List.of(blankAuthor), List.of(noAuthors), scientificProductionService
        );
        assertEquals(0.0, score);
    }

    @Test
    void computeCriterionScoresSkipsNullAndOutOfRangeIndices() {
        Indicator i0 = new Indicator();
        i0.setId("i0");
        Indicator i1 = new Indicator();
        i1.setId("i1");
        List<Indicator> indicators = List.of(i0, i1);

        AbstractReport.Criterion c0 = new AbstractReport.Criterion();
        c0.setIndicatorIndices(new ArrayList<>(java.util.Arrays.asList(0, 1, -1, 2, null)));
        AbstractReport.Criterion c1 = new AbstractReport.Criterion();
        c1.setIndicatorIndices(null);

        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(c0, c1),
                indicators,
                Map.of("i0", 2.0, "i1", 3.0)
        );

        assertEquals(5.0, out.get(0), 0.0001);
        assertEquals(0.0, out.get(1), 0.0001);
    }

    @Test
    void computeCriterionScoresAppliesPerIndicatorWeightsForComposites() {
        // H65: physics T = ... + I/2 + P/2 ... — a weighted-sum criterion. Indicator 0 (I)=4.0, indicator 1 (P)=6.0.
        Indicator i = new Indicator();
        i.setId("I");
        Indicator p = new Indicator();
        p.setId("P");
        List<Indicator> indicators = List.of(i, p);

        AbstractReport.Criterion weighted = new AbstractReport.Criterion();
        weighted.setIndicatorIndices(new ArrayList<>(java.util.List.of(0, 1)));
        weighted.setWeights(java.util.Map.of(0, 0.5, 1, 0.5));
        AbstractReport.Criterion plain = new AbstractReport.Criterion();
        plain.setIndicatorIndices(new ArrayList<>(java.util.List.of(0, 1))); // no weights → plain sum (unchanged behavior)

        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(weighted, plain), indicators, Map.of("I", 4.0, "P", 6.0));

        assertEquals(5.0, out.get(0), 0.0001);  // 0.5*4 + 0.5*6
        assertEquals(10.0, out.get(1), 0.0001); // plain 4 + 6 (weights null → 1.0)
    }

    private static Score totalScore(double value) {
        Score score = new Score();
        score.setAuthorScore(value);
        score.setScore(value);
        return score;
    }

    private static ScholardexPublicationView publication(String id, List<String> authorIds) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setAuthors(authorIds);
        return publication;
    }
}
