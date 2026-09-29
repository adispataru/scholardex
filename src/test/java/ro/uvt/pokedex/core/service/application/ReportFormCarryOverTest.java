package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.AbstractReport.CompositionNode;
import ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion;
import ro.uvt.pokedex.core.model.reporting.AbstractReport.Perspective;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Edge cases of the carry-over rule; the page-level behaviour is in AdminIndividualReportFormRoundTripTest. */
class ReportFormCarryOverTest {

    private static Indicator indicator(String id) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setName(id.toUpperCase());
        return indicator;
    }

    private static Criterion criterion(String name, Integer... indicatorIndices) {
        Criterion criterion = new Criterion();
        criterion.setName(name);
        criterion.setIndicatorIndices(new ArrayList<>(List.of(indicatorIndices)));
        return criterion;
    }

    private static IndividualReport report(List<String> indicatorIds, Criterion... criteria) {
        IndividualReport report = new IndividualReport();
        report.setId("r");
        report.setIndicators(new ArrayList<>(indicatorIds.stream().map(ReportFormCarryOverTest::indicator).toList()));
        report.setCriteria(new ArrayList<>(List.of(criteria)));
        return report;
    }

    private static List<Perspective> onePerspectiveOver(int criterionIndex) {
        CompositionNode leaf = new CompositionNode();
        leaf.setCriterion(criterionIndex);
        CompositionNode root = new CompositionNode();
        root.setAll(new ArrayList<>(List.of(leaf)));
        Perspective perspective = new Perspective();
        perspective.setName("P");
        perspective.setComposition(root);
        return new ArrayList<>(List.of(perspective));
    }

    private static Criterion weighted(Criterion criterion, int indicatorIndex, double weight) {
        criterion.setWeights(new LinkedHashMap<>(Map.of(indicatorIndex, weight)));
        return criterion;
    }

    @Test
    void renamingIsFineAndSoIsChangingTheIndicatorsButNotBothAtOnce() {
        IndividualReport stored = report(List.of("a", "b"), weighted(criterion("Total", 0, 1), 1, 0.5));

        IndividualReport renamed = report(List.of("a", "b"), criterion("Punctaj total", 0, 1));
        assertTrue(ReportFormCarryOver.apply(stored, renamed).isEmpty());
        assertEquals(Map.of(1, 0.5), renamed.getCriteria().getFirst().getWeights());

        IndividualReport narrowed = report(List.of("a", "b"), criterion("Total", 1));
        assertTrue(ReportFormCarryOver.apply(stored, narrowed).isEmpty());
        assertEquals(Map.of(1, 0.5), narrowed.getCriteria().getFirst().getWeights());

        IndividualReport both = report(List.of("a", "b"), criterion("Altceva", 1));
        Optional<String> refusal = ReportFormCarryOver.apply(stored, both);
        assertTrue(refusal.orElseThrow().contains("criterion 1 (\"Total\")"));
        assertNull(both.getCriteria().getFirst().getWeights(), "a refused report must be left as posted");
    }

    @Test
    void namesAreComparedWithoutSurroundingSpaces() {
        IndividualReport stored = report(List.of("a", "b"), weighted(criterion("Total", 0, 1), 1, 0.5));
        IndividualReport posted = report(List.of("a", "b"), criterion("  Total ", 0));

        assertTrue(ReportFormCarryOver.apply(stored, posted).isEmpty());
    }

    @Test
    void perspectivesAloneDoNotCareAboutTheIndicatorList() {
        // Perspectives point at criteria only, so reshuffling indicators cannot make them stale.
        IndividualReport stored = report(List.of("a", "b"), criterion("C", 0, 1));
        stored.setPerspectives(onePerspectiveOver(0));
        IndividualReport posted = report(List.of("b"), criterion("C", 0));

        assertTrue(ReportFormCarryOver.apply(stored, posted).isEmpty());
        assertEquals(stored.getPerspectives(), posted.getPerspectives());
    }

    @Test
    void indexKeyedCriterionFieldsNeedEveryStoredIndicatorInPlace() {
        IndividualReport stored = report(List.of("a", "b", "c"), weighted(criterion("Total", 0, 1, 2), 2, 0.2));

        IndividualReport swapped = report(List.of("a", "c", "b"), criterion("Total", 0, 1, 2));
        assertTrue(ReportFormCarryOver.apply(stored, swapped).orElseThrow().contains("indicator 2 (\"B\")"));

        IndividualReport shortened = report(List.of("a", "b"), criterion("Total", 0, 1, 2));
        assertTrue(ReportFormCarryOver.apply(stored, shortened).orElseThrow().contains("indicator 3 (\"C\")"));

        IndividualReport extended = report(List.of("a", "b", "c", "d"), criterion("Total", 0, 1, 2));
        assertTrue(ReportFormCarryOver.apply(stored, extended).isEmpty());
        assertEquals(Map.of(2, 0.2), extended.getCriteria().getFirst().getWeights());
    }

    @Test
    void aReportWithNothingScriptedIsNeverRefusedAndNeverTouched() {
        IndividualReport stored = report(List.of("a", "b"), criterion("A", 0), criterion("B", 1));
        stored.getCriteria().get(1).setWeights(new LinkedHashMap<>()); // empty counts as nothing
        stored.setPerspectives(new ArrayList<>());
        IndividualReport posted = report(List.of("b"), criterion("Z", 0));

        assertTrue(ReportFormCarryOver.apply(stored, posted).isEmpty());
        assertNull(posted.getPerspectives());
        assertNull(posted.getCriteria().getFirst().getWeights());
    }

    @Test
    void whatTheRequestStatesIsKeptFieldByField() {
        Criterion kept = weighted(criterion("Total", 0, 1), 1, 0.5);
        kept.setMaxPercentOfTotal(new LinkedHashMap<>(Map.of(0, 10.0)));
        IndividualReport stored = report(List.of("a", "b"), kept);
        stored.setPerspectives(onePerspectiveOver(0));

        IndividualReport posted = report(List.of("a", "b"), weighted(criterion("Total", 0, 1), 1, 0.9));
        List<Perspective> stated = onePerspectiveOver(0);
        stated.getFirst().setName("Stated by the request");
        posted.setPerspectives(stated);

        assertTrue(ReportFormCarryOver.apply(stored, posted).isEmpty());
        assertEquals("Stated by the request", posted.getPerspectives().getFirst().getName());
        assertEquals(Map.of(1, 0.9), posted.getCriteria().getFirst().getWeights());
        assertEquals(Map.of(0, 10.0), posted.getCriteria().getFirst().getMaxPercentOfTotal());
    }

    @Test
    void aRequestThatStatesEverythingAtStakeMayChangeTheShape() {
        // A complete re-post (a script going through the endpoint) is its own source of truth: every
        // position that holds scripted fields in the stored report states them again, so nothing is carried.
        IndividualReport stored = report(List.of("a", "b"), criterion("A", 0), weighted(criterion("B", 1), 1, 0.5));
        stored.setPerspectives(onePerspectiveOver(1));
        IndividualReport posted = report(List.of("b", "a"),
                criterion("Nou", 1), weighted(criterion("Alt", 0), 0, 0.7));
        posted.setPerspectives(onePerspectiveOver(0));

        assertTrue(ReportFormCarryOver.apply(stored, posted).isEmpty());
        assertEquals(Map.of(0, 0.7), posted.getCriteria().get(1).getWeights());
        assertEquals(0, posted.getPerspectives().getFirst().getComposition().getAll().getFirst().getCriterion());
    }

    @Test
    void aScriptedCriterionWithoutACounterpartIsARefusalWhateverElseTheRequestStates() {
        // Removing the first of two criteria moves the weighted one up. Its weights have nowhere safe to
        // go, and saving without them would be the silent wipe this class exists to prevent.
        IndividualReport stored = report(List.of("a", "b"), criterion("A", 0), weighted(criterion("B", 1), 1, 0.5));
        IndividualReport posted = report(List.of("a", "b"), criterion("B", 1));
        posted.setPerspectives(onePerspectiveOver(0));

        assertTrue(ReportFormCarryOver.apply(stored, posted).isPresent());
        assertNull(posted.getCriteria().getFirst().getWeights());
    }

    @Test
    void missingListsAndNamesAreHandled() {
        IndividualReport stored = report(List.of("a"), weighted(criterion(null, 0), 0, 0.5));
        stored.setPerspectives(onePerspectiveOver(0));

        IndividualReport sameUnnamed = report(List.of("a"), criterion(null, 0));
        assertTrue(ReportFormCarryOver.apply(stored, sameUnnamed).isEmpty());
        assertEquals(Map.of(0, 0.5), sameUnnamed.getCriteria().getFirst().getWeights());

        IndividualReport noCriteria = new IndividualReport();
        noCriteria.setId("r");
        noCriteria.setCriteria(null);
        noCriteria.setIndicators(null);
        assertTrue(ReportFormCarryOver.apply(stored, noCriteria).orElseThrow().contains("criterion 1"));

        IndividualReport nothingStored = new IndividualReport();
        nothingStored.setCriteria(null);
        nothingStored.setIndicators(null);
        assertTrue(ReportFormCarryOver.apply(nothingStored, noCriteria).isEmpty());
    }

    @Test
    void theIndicatorsAShareCriterionIsMeasuredAgainstSurviveASave() {
        // Set by a script (Comisia 25, C.2/C.3); the form has no input for it.
        Criterion stored = criterion("C2", 1);
        stored.setShareOfIndicatorIndices(new ArrayList<>(List.of(0)));
        IndividualReport kept = report(List.of("i1", "i1n"), stored);
        IndividualReport posted = report(List.of("i1", "i1n"), criterion("C2 renamed", 1));

        Optional<String> refusal = ReportFormCarryOver.apply(kept, posted);

        assertTrue(refusal.isEmpty());
        assertEquals(List.of(0), posted.getCriteria().get(0).getShareOfIndicatorIndices());
    }
}
