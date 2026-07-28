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

    @Test
    void computeCriterionScoresAppliesCriterionLevelCap() {
        // H68 slice 2: maxTotal clamps the aggregated (post-weight) criterion score.
        Indicator a = new Indicator();
        a.setId("A");
        Indicator b = new Indicator();
        b.setId("B");
        List<Indicator> indicators = List.of(a, b);

        AbstractReport.Criterion capped = new AbstractReport.Criterion();
        capped.setIndicatorIndices(new ArrayList<>(java.util.List.of(0, 1)));
        capped.setMaxTotal(50.0);
        AbstractReport.Criterion uncapped = new AbstractReport.Criterion();
        uncapped.setIndicatorIndices(new ArrayList<>(java.util.List.of(0, 1)));

        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(capped, uncapped), indicators, Map.of("A", 40.0, "B", 30.0)); // sum 70

        assertEquals(50.0, out.get(0), 0.0001); // clamped to maxTotal
        assertEquals(70.0, out.get(1), 0.0001); // no cap → full sum
    }

    // ── H68 slice 3: percent-of-criterion caps (fixed-point semantics, pinned 2026-07-24) ──

    private static List<Indicator> percentCapIndicators() {
        Indicator rest = new Indicator();
        rest.setId("rest");
        Indicator f1 = new Indicator();
        f1.setId("f1");
        Indicator f2 = new Indicator();
        f2.setId("f2");
        return List.of(rest, f1, f2);
    }

    private static AbstractReport.Criterion percentCapCriterion(java.util.Map<Integer, Double> percents) {
        AbstractReport.Criterion c = new AbstractReport.Criterion();
        c.setIndicatorIndices(new ArrayList<>(java.util.List.of(0, 1, 2)));
        c.setMaxPercentOfTotal(percents);
        return c;
    }

    @Test
    void percentCapFixedPointBindsAtExactlyTenPercentOfFinalTotal() {
        // The scope's numeric pin: rest=90, flagged raw=30, p=10% → T = 90/0.9 = 100, cap = 10.
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(percentCapCriterion(java.util.Map.of(1, 10.0))),
                percentCapIndicators(),
                Map.of("rest", 90.0, "f1", 30.0, "f2", 0.0));
        assertEquals(100.0, out.get(0), 0.0001);
    }

    @Test
    void percentCapLeavesNonBindingContributionUntouched() {
        // rest=90, flagged raw=5 → 5 < 10% of 95, cap does not bind, plain sum.
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(percentCapCriterion(java.util.Map.of(1, 10.0))),
                percentCapIndicators(),
                Map.of("rest", 90.0, "f1", 5.0, "f2", 0.0));
        assertEquals(95.0, out.get(0), 0.0001);
    }

    @Test
    void percentCapTwoFlaggedBothBinding() {
        // rest=80, raws 30+40, p=10% each → T = 80/0.8 = 100, caps 10+10, total 100.
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(percentCapCriterion(java.util.Map.of(1, 10.0, 2, 10.0))),
                percentCapIndicators(),
                Map.of("rest", 80.0, "f1", 30.0, "f2", 40.0));
        assertEquals(100.0, out.get(0), 0.0001);
    }

    @Test
    void percentCapReleasesFlaggedIndicatorThatFitsUnderTheFixedPoint() {
        // rest=90, f1 raw=30 (binds), f2 raw=2 (fits): assume both bind → T=90/0.8=112.5, but 2 ≤ 11.25 so
        // f2 releases; recompute with f1 only → T=(90+2)/0.9=102.222…, cap f1=10.2222, total 102.2222.
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(percentCapCriterion(java.util.Map.of(1, 10.0, 2, 10.0))),
                percentCapIndicators(),
                Map.of("rest", 90.0, "f1", 30.0, "f2", 2.0));
        assertEquals(92.0 / 0.9, out.get(0), 0.0001);
    }

    @Test
    void percentCapZeroRestWithAllPointsFlaggedYieldsZero() {
        // Documented faithful edge: every point is percent-capped and rest=0 → T=0.
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(percentCapCriterion(java.util.Map.of(1, 10.0, 2, 10.0))),
                percentCapIndicators(),
                Map.of("rest", 0.0, "f1", 30.0, "f2", 40.0));
        assertEquals(0.0, out.get(0), 0.0001);
    }

    @Test
    void percentCapDegenerateSumFallsBackToPercentOfRest() {
        // Binding percents sum to 120% — the fixed point diverges; fall back to percent-of-rest:
        // caps = 60%·90 + 60%·90 = 54+54, total = 90+108 = 198 (and defined, not infinite).
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(percentCapCriterion(java.util.Map.of(1, 60.0, 2, 60.0))),
                percentCapIndicators(),
                Map.of("rest", 90.0, "f1", 500.0, "f2", 500.0));
        assertEquals(198.0, out.get(0), 0.0001);
    }

    @Test
    void percentCapComposesWithWeightsAndMaxTotal() {
        // Weight halves the flagged raw 60 → contribution 30; fixed point on rest=90 → cap 10, T=100;
        // then maxTotal=95 clamps last.
        AbstractReport.Criterion c = percentCapCriterion(java.util.Map.of(1, 10.0));
        c.setWeights(java.util.Map.of(1, 0.5));
        c.setMaxTotal(95.0);
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(c), percentCapIndicators(), Map.of("rest", 90.0, "f1", 60.0, "f2", 0.0));
        assertEquals(95.0, out.get(0), 0.0001);
    }

    @Test
    void percentCapFeedsTheEffectiveScoreIntoOtherCriteria() {
        // The Informatică "Total" shape: criterion 0 declares the cap (rest=90, raw=30 → effective 10, T=100);
        // criterion 1 (Total) references the same flagged indicator WITHOUT declaring a cap — it must count
        // the capped 10, not the raw 30 (the OM caps the item's points, not just one criterion's sum).
        AbstractReport.Criterion perspective = percentCapCriterion(java.util.Map.of(1, 10.0));
        AbstractReport.Criterion total = new AbstractReport.Criterion();
        total.setIndicatorIndices(new ArrayList<>(java.util.List.of(0, 1, 2)));
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(perspective, total),
                percentCapIndicators(),
                Map.of("rest", 90.0, "f1", 30.0, "f2", 0.0));
        assertEquals(100.0, out.get(0), 0.0001);
        assertEquals(100.0, out.get(1), 0.0001);
    }

    @Test
    void criterionWithoutPercentCapsIsUnchanged() {
        // Legacy criterion docs deserialize with a null map — behavior identical to before slice 3.
        AbstractReport.Criterion c = percentCapCriterion(null);
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(c), percentCapIndicators(), Map.of("rest", 90.0, "f1", 30.0, "f2", 40.0));
        assertEquals(160.0, out.get(0), 0.0001);
    }

    @Test
    void byIndicatorOverloadAppliesWeightsAndCapAndReportsInvalidIndices() {
        // H68 slice 1: the group paths (IndividualReportComputer / GroupReportRunner) delegate here with scores keyed
        // by Indicator; weights + cap must apply (previously they did a plain sum) and invalid indices reach errors.
        Indicator i = new Indicator();
        i.setId("I");
        Indicator p = new Indicator();
        p.setId("P");
        List<Indicator> indicators = List.of(i, p);

        AbstractReport.Criterion weighted = new AbstractReport.Criterion();
        weighted.setIndicatorIndices(new ArrayList<>(java.util.List.of(0, 1)));
        weighted.setWeights(java.util.Map.of(0, 0.5, 1, 0.5));
        AbstractReport.Criterion cappedBadIndex = new AbstractReport.Criterion();
        cappedBadIndex.setIndicatorIndices(new ArrayList<>(java.util.Arrays.asList(0, 5)));
        cappedBadIndex.setMaxTotal(3.0);

        List<String> errors = new ArrayList<>();
        Map<Integer, Double> out = ReportingComputationSupport.computeCriterionScores(
                List.of(weighted, cappedBadIndex), indicators,
                new LinkedHashMap<>(Map.of(i, 4.0, p, 6.0)), errors);

        assertEquals(5.0, out.get(0), 0.0001);            // 0.5*4 + 0.5*6 — weights applied (the bug fix)
        assertEquals(3.0, out.get(1), 0.0001);            // 4.0 for I clamped to maxTotal 3.0 (index 5 skipped)
        assertTrue(errors.stream().anyMatch(e -> e.contains("Invalid indicator index 5")));
    }

    // ── Stage 1: threshold-cap additions (position-aware eligibility, FEAA 2026 book cap) ──

    /** FEAA-2026-shaped fixture: P (articles), C (citations), Books (outside every criterion), S = P+C. */
    private static List<Indicator> capAdditionIndicators() {
        Indicator p = new Indicator();
        p.setId("P");
        p.setName("FEEA_P");
        Indicator c = new Indicator();
        c.setId("C");
        c.setName("FEEA_C");
        Indicator books = new Indicator();
        books.setId("Books");
        books.setName("FEEA_Books");
        return List.of(p, c, books);
    }

    private static AbstractReport.Threshold threshold(ro.uvt.pokedex.core.model.reporting.Position position,
                                                      double value) {
        AbstractReport.Threshold t = new AbstractReport.Threshold();
        t.setPosition(position);
        t.setValue(value);
        return t;
    }

    private static AbstractReport.ThresholdCapAddition addition(int indicatorIndex, double percent,
                                                                Integer thresholdCriterionIndex) {
        AbstractReport.ThresholdCapAddition a = new AbstractReport.ThresholdCapAddition();
        a.setIndicatorIndex(indicatorIndex);
        a.setPercent(percent);
        a.setThresholdCriterionIndex(thresholdCriterionIndex);
        return a;
    }

    /** P criterion (index 0) with per-position thresholds 1.25 (CONF) / 3.0 (PROF) and the books addition. */
    private static List<AbstractReport.Criterion> feaaShapedCriteria(Integer refIndexOnS) {
        AbstractReport.Criterion p = new AbstractReport.Criterion();
        p.setIndicatorIndices(new ArrayList<>(List.of(0)));
        p.setThresholds(new ArrayList<>(List.of(
                threshold(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 1.25),
                threshold(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 3.0))));
        p.setThresholdCapAdditions(new ArrayList<>(List.of(addition(2, 25.0, null))));

        AbstractReport.Criterion s = new AbstractReport.Criterion();
        s.setIndicatorIndices(new ArrayList<>(List.of(0, 1)));
        s.setThresholds(new ArrayList<>(List.of(
                threshold(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 2.25),
                threshold(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 6.0))));
        s.setThresholdCapAdditions(new ArrayList<>(List.of(addition(2, 25.0, refIndexOnS))));
        return List.of(p, s);
    }

    @Test
    void positionEffectiveScoresCapAdditionAtPercentOfOwnThreshold() {
        // Books raw 0.9 exceeds both caps: CONF 25%·1.25=0.3125, PROF 25%·3=0.75.
        Map<Integer, Map<String, Double>> out = ReportingComputationSupport.computePositionEffectiveScores(
                feaaShapedCriteria(0), capAdditionIndicators(),
                Map.of("P", 1.0, "C", 0.5, "Books", 0.9),
                Map.of(0, 1.0, 1, 1.5));

        assertEquals(1.0 + 0.3125, out.get(0).get("CONF_UNIV"), 0.0001);
        assertEquals(1.0 + 0.75, out.get(0).get("PROF_UNIV"), 0.0001);
    }

    @Test
    void positionEffectiveScoresCrossCriterionReferenceUsesReferencedThreshold() {
        // The S criterion's addition is capped by P's threshold (index 0), not S's own — the FEAA shape.
        Map<Integer, Map<String, Double>> out = ReportingComputationSupport.computePositionEffectiveScores(
                feaaShapedCriteria(0), capAdditionIndicators(),
                Map.of("P", 1.0, "C", 0.5, "Books", 0.9),
                Map.of(0, 1.0, 1, 1.5));

        assertEquals(1.5 + 0.3125, out.get(1).get("CONF_UNIV"), 0.0001); // 25% of P's 1.25, NOT of S's 2.25
        assertEquals(1.5 + 0.75, out.get(1).get("PROF_UNIV"), 0.0001);
    }

    @Test
    void positionEffectiveScoresNonBindingAdditionAddsRawValue() {
        Map<Integer, Map<String, Double>> out = ReportingComputationSupport.computePositionEffectiveScores(
                feaaShapedCriteria(0), capAdditionIndicators(),
                Map.of("P", 1.0, "C", 0.5, "Books", 0.2), // 0.2 < both caps
                Map.of(0, 1.0, 1, 1.5));

        assertEquals(1.2, out.get(0).get("CONF_UNIV"), 0.0001);
        assertEquals(1.2, out.get(0).get("PROF_UNIV"), 0.0001);
    }

    @Test
    void positionEffectiveScoresSkipAdditionAlreadySummedIntoCriterion() {
        // Misconfiguration guard: indicator 0 is inside the criterion — adding it again would double-count.
        AbstractReport.Criterion c = new AbstractReport.Criterion();
        c.setIndicatorIndices(new ArrayList<>(List.of(0)));
        c.setThresholds(new ArrayList<>(List.of(
                threshold(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 1.25))));
        c.setThresholdCapAdditions(new ArrayList<>(List.of(addition(0, 25.0, null))));

        Map<Integer, Map<String, Double>> out = ReportingComputationSupport.computePositionEffectiveScores(
                List.of(c), capAdditionIndicators(), Map.of("P", 1.0), Map.of(0, 1.0));

        assertEquals(1.0, out.get(0).get("CONF_UNIV"), 0.0001); // unchanged — addition skipped
    }

    @Test
    void positionEffectiveScoresAbsentForCriteriaWithoutAdditionsAndForMissingPositions() {
        List<AbstractReport.Criterion> criteria = feaaShapedCriteria(0);
        AbstractReport.Criterion legacy = new AbstractReport.Criterion();
        legacy.setIndicatorIndices(new ArrayList<>(List.of(1)));
        legacy.setThresholds(new ArrayList<>(List.of(
                threshold(ro.uvt.pokedex.core.model.reporting.Position.LECT_UNIV, 0.4))));
        List<AbstractReport.Criterion> all = new ArrayList<>(criteria);
        all.add(legacy);

        Map<Integer, Map<String, Double>> out = ReportingComputationSupport.computePositionEffectiveScores(
                all, capAdditionIndicators(), Map.of("P", 1.0, "C", 0.5, "Books", 0.9),
                Map.of(0, 1.0, 1, 1.5, 2, 0.4));

        assertFalse(out.containsKey(2)); // legacy criterion: no additions → absent
        assertFalse(out.get(0).containsKey("LECT_UNIV")); // no LECT threshold on P → no entry
    }

    @Test
    void positionEffectiveScoresComposePerPositionIndicatorTotals() {
        // S2 (FV Info 2016 D-gate): the Conferințe indicator's Poz formula diverges for Conf/Prof
        // (D points cut); the criterion's effective score shifts by the weighted delta, canonical stays.
        Indicator conferences = new Indicator();
        conferences.setId("conf");
        conferences.setName("Info_B_Conferințe");
        Indicator journals = new Indicator();
        journals.setId("jour");
        journals.setName("Info_B_Jurnale");
        List<Indicator> indicators = List.of(conferences, journals);

        AbstractReport.Criterion b = new AbstractReport.Criterion();
        b.setIndicatorIndices(new ArrayList<>(List.of(0, 1)));
        b.setThresholds(new ArrayList<>(List.of(
                threshold(ro.uvt.pokedex.core.model.reporting.Position.LECT_UNIV, 12.0),
                threshold(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 32.0))));

        Map<Integer, Map<String, Double>> out = ReportingComputationSupport.computePositionEffectiveScores(
                List.of(b), indicators,
                Map.of("conf", 5.0, "jour", 30.0),   // canonical: D counted → criterion 35
                Map.of(0, 35.0),
                Map.of("conf", Map.of("CONF_UNIV", 4.0))); // Conf diverges: D (1 point) cut

        assertEquals(34.0, out.get(0).get("CONF_UNIV"), 0.0001); // 35 + (4 − 5)
        assertEquals(35.0, out.get(0).get("LECT_UNIV"), 0.0001); // no divergence → canonical
    }

    @Test
    void positionEffectiveScoresAbsentWithoutAdditionsOrPerPositionTotals() {
        Indicator plain = new Indicator();
        plain.setId("p");
        AbstractReport.Criterion c = new AbstractReport.Criterion();
        c.setIndicatorIndices(new ArrayList<>(List.of(0)));
        c.setThresholds(new ArrayList<>(List.of(
                threshold(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 1.0))));

        Map<Integer, Map<String, Double>> out = ReportingComputationSupport.computePositionEffectiveScores(
                List.of(c), List.of(plain), Map.of("p", 2.0), Map.of(0, 2.0), Map.of());

        assertTrue(out.isEmpty());
    }

    @Test
    void thresholdCapNotesOnlyForPositiveRawValues() {
        Map<Integer, Map<String, List<String>>> notes = ReportingComputationSupport.buildThresholdCapNotes(
                feaaShapedCriteria(0), capAdditionIndicators(),
                Map.of("P", 1.0, "C", 0.5, "Books", 0.9));

        assertTrue(notes.get(0).get("CONF_UNIV").get(0).contains("FEEA_Books"));
        assertTrue(notes.get(0).get("CONF_UNIV").get(0).contains("+0.31"));
        assertTrue(notes.get(0).get("PROF_UNIV").get(0).contains("+0.75"));

        Map<Integer, Map<String, List<String>>> silent = ReportingComputationSupport.buildThresholdCapNotes(
                feaaShapedCriteria(0), capAdditionIndicators(),
                Map.of("P", 1.0, "C", 0.5, "Books", 0.0));
        assertTrue(silent.isEmpty()); // zero raw → nothing to annotate
    }

    // ── H95: perspective verdicts (AND/OR trees over criteria met-ness) ──

    private static AbstractReport.CompositionNode leaf(int criterion) {
        AbstractReport.CompositionNode n = new AbstractReport.CompositionNode();
        n.setCriterion(criterion);
        return n;
    }

    private static AbstractReport.CompositionNode perspectiveLeaf(int perspective) {
        AbstractReport.CompositionNode n = new AbstractReport.CompositionNode();
        n.setPerspective(perspective);
        return n;
    }

    private static AbstractReport.CompositionNode all(AbstractReport.CompositionNode... children) {
        AbstractReport.CompositionNode n = new AbstractReport.CompositionNode();
        n.setAll(new ArrayList<>(List.of(children)));
        return n;
    }

    private static AbstractReport.CompositionNode any(AbstractReport.CompositionNode... children) {
        AbstractReport.CompositionNode n = new AbstractReport.CompositionNode();
        n.setAny(new ArrayList<>(List.of(children)));
        return n;
    }

    private static AbstractReport.Perspective perspective(String name, AbstractReport.CompositionNode composition) {
        AbstractReport.Perspective p = new AbstractReport.Perspective();
        p.setName(name);
        p.setComposition(composition);
        return p;
    }

    private static AbstractReport.Criterion thresholdCriterion(Object... positionValuePairs) {
        AbstractReport.Criterion c = new AbstractReport.Criterion();
        List<AbstractReport.Threshold> thresholds = new ArrayList<>();
        for (int i = 0; i < positionValuePairs.length; i += 2) {
            thresholds.add(threshold((ro.uvt.pokedex.core.model.reporting.Position) positionValuePairs[i],
                    (Double) positionValuePairs[i + 1]));
        }
        c.setThresholds(thresholds);
        return c;
    }

    @Test
    void perspectiveAllRequiresEveryApplicableLeaf() {
        // FV Info B shape (conf): total >= 32 AND topAB >= 16; the A*+A gate exists only for PROF and is
        // skipped at CONF (vacuous true in `all`) — the excel's E9 vs E10 difference.
        var total = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 32.0,
                ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 56.0);
        var topAB = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 16.0,
                ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 40.0);
        var topAStarA = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 24.0);

        Map<Integer, Map<String, Boolean>> verdicts = ReportingComputationSupport.computePerspectiveVerdicts(
                List.of(perspective("Perspectiva B", all(leaf(0), leaf(1), leaf(2)))),
                List.of(total, topAB, topAStarA),
                Map.of(0, 40.0, 1, 20.0, 2, 10.0), // conf passes both gates; prof fails all three
                Map.of());

        assertTrue(verdicts.get(0).get("CONF_UNIV"));  // A*+A leaf skipped at CONF
        assertFalse(verdicts.get(0).get("PROF_UNIV")); // 40 < 56 etc.
    }

    @Test
    void perspectiveAnyTreatsInapplicableLeavesAsNotSatisfied() {
        // FEAA P4 prof shape: any[a, all[b1, b2]] — route criteria without a CONF threshold must not make
        // the disjunction vacuously true at CONF.
        var artGe3 = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 3.0);
        var grantsGe3 = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 3.0);
        var dirGe2 = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 2.0);
        var confOnly = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 1.0);

        Map<Integer, Map<String, Boolean>> verdicts = ReportingComputationSupport.computePerspectiveVerdicts(
                List.of(perspective("P4", any(leaf(0), all(leaf(1), leaf(2))))),
                List.of(artGe3, grantsGe3, dirGe2, confOnly),
                Map.of(0, 0.0, 1, 3.0, 2, 2.0, 3, 1.0), // route a fails, route b passes
                Map.of());

        assertTrue(verdicts.get(0).get("PROF_UNIV"));       // any: route b (all of b1,b2) fires
        assertFalse(verdicts.get(0).containsKey("CONF_UNIV")); // no leaf applicable at CONF → inapplicable
    }

    @Test
    void perspectiveLeafReferencesEarlierVerdictAndUsesEffectiveScores() {
        // Total-verdict shape: all[persp 0, criterion 1] — and criterion 1's met-ness uses the
        // position-EFFECTIVE score (Stage 1/S2) when one exists.
        var member = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 5.0);
        var sum = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 100.0);

        Map<Integer, Map<String, Boolean>> verdicts = ReportingComputationSupport.computePerspectiveVerdicts(
                List.of(perspective("B", all(leaf(0))),
                        perspective("Total", all(perspectiveLeaf(0), leaf(1)))),
                List.of(member, sum),
                Map.of(0, 6.0, 1, 90.0),                       // canonical sum 90 < 100…
                Map.of(1, Map.of("CONF_UNIV", 105.0)));        // …but effective 105 passes

        assertTrue(verdicts.get(0).get("CONF_UNIV"));
        assertTrue(verdicts.get(1).get("CONF_UNIV"));
    }

    @Test
    void malformedCompositionDisablesOnlyThatPerspective() {
        var c = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 1.0);

        Map<Integer, Map<String, Boolean>> verdicts = ReportingComputationSupport.computePerspectiveVerdicts(
                List.of(perspective("forward-ref", all(perspectiveLeaf(1))),  // refs a LATER perspective
                        perspective("ok", all(leaf(0)))),
                List.of(c), Map.of(0, 2.0), Map.of());

        assertFalse(verdicts.containsKey(0));
        assertTrue(verdicts.get(1).get("CONF_UNIV"));
    }

    @Test
    void perspectiveRoutesEmitPerChildVerdictsForAnyRootsOnly() {
        // FEAA P4 shape: any[a: art>=3, b: all[grants>=3, dir>=2]] with authored labels. Routes may
        // share criteria and must reuse the verdict evaluator's vacuity rules per position.
        var artGe3 = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 3.0,
                ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV, 1.0);
        var grantsGe3 = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 3.0);
        var dirGe2 = thresholdCriterion(ro.uvt.pokedex.core.model.reporting.Position.PROF_UNIV, 2.0);

        var routeA = leaf(0);
        routeA.setLabel("Ruta a");
        var routeB = all(leaf(1), leaf(2));
        routeB.setLabel("Ruta b");

        Map<Integer, List<ReportingComputationSupport.PerspectiveRoute>> routes =
                ReportingComputationSupport.computePerspectiveRoutes(
                        List.of(perspective("P4", any(routeA, routeB)),
                                perspective("all-root", all(leaf(0), leaf(1))),
                                perspective("single-any", any(leaf(0), leaf(0)))),
                        List.of(artGe3, grantsGe3, dirGe2),
                        Map.of(0, 0.0, 1, 3.0, 2, 2.0), Map.of());

        assertEquals(2, routes.get(0).size());
        var a = routes.get(0).get(0);
        assertEquals("Ruta a", a.label());
        assertEquals(List.of(0), a.members());
        assertFalse(a.verdictByPosition().get("PROF_UNIV")); // 0 < 3
        assertFalse(a.verdictByPosition().get("CONF_UNIV")); // 0 < 1
        var b = routes.get(0).get(1);
        assertEquals("Ruta b", b.label());
        assertEquals(List.of(1, 2), b.members());
        assertTrue(b.verdictByPosition().get("PROF_UNIV"));  // 3>=3 AND 2>=2
        assertFalse(b.verdictByPosition().containsKey("CONF_UNIV")); // no CONF thresholds → inapplicable

        assertFalse(routes.containsKey(1)); // all-rooted perspectives get no route legend
        assertTrue(routes.containsKey(2));  // any with 2 children qualifies even with repeated leaves
    }

    @Test
    void bundledCriterionIndicesCollectsDirectRefsOnly() {
        var perspectives = List.of(
                perspective("B", all(leaf(0), leaf(2))),
                perspective("Total", all(perspectiveLeaf(0), leaf(5))));
        assertEquals(java.util.Set.of(0, 2, 5),
                ReportingComputationSupport.bundledCriterionIndices(perspectives));
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
