package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.ReportComparisonViewModel;
import ro.uvt.pokedex.core.service.application.model.ReportComparisonViewModel.CriterionComparisonRow;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportComparisonFacadeTest {

    @Mock
    private UserReportFacade userReportFacade;
    @Mock
    private UserIndividualReportRunService userIndividualReportRunService;

    private ReportComparisonFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ReportComparisonFacade(userReportFacade, userIndividualReportRunService);
    }

    private IndividualReport report2016() {
        IndividualReport r = new IndividualReport();
        r.setId("report-2016");
        r.setTitle("FV Info 2016");
        r.setReportTypeKey("informatica-2016");
        r.setCriteria(List.of(
                criterion("Perspectiva B", true, Position.CONF_UNIV, 32.0),
                criterion("Perspectiva C", true, Position.CONF_UNIV, 48.0),
                criterion("Perspectiva D", true, Position.CONF_UNIV, 36.0)
        ));
        return r;
    }

    private IndividualReport report2026() {
        IndividualReport r = new IndividualReport();
        r.setId("report-2026");
        r.setTitle("FV Info 2026");
        r.setReportTypeKey("informatica-2026");
        r.setCriteria(List.of(
                criterion("Perspectiva B", true, Position.CONF_UNIV, 32.0),
                criterion("Perspectiva C", true, Position.CONF_UNIV, 48.0),
                criterion("Perspectiva D", true, Position.CONF_UNIV, 36.0),
                criterion("Publicații A*+A", false, Position.PROF_UNIV, 24.0)
        ));
        return r;
    }

    private AbstractReport.Criterion criterion(String name, boolean contributesToTotal, Position position, double threshold) {
        AbstractReport.Criterion c = new AbstractReport.Criterion();
        c.setName(name);
        c.setContributesToTotal(contributesToTotal);
        AbstractReport.Threshold t = new AbstractReport.Threshold();
        t.setPosition(position);
        t.setValue(threshold);
        c.setThresholds(List.of(t));
        return c;
    }

    @Test
    void findCompatibleReportReturnsTheOtherSideWhenBothAssigned() {
        IndividualReport older = report2016();
        IndividualReport newer = report2026();
        when(userReportFacade.buildIndividualReportsListView("r@example.com"))
                .thenReturn(new UserReportsListViewModel(List.of(older, newer)));

        Optional<IndividualReport> found = facade.findCompatibleReport("r@example.com", newer);

        assertTrue(found.isPresent());
        assertEquals("report-2016", found.get().getId());
    }

    @Test
    void findCompatibleReportResolvesFromEitherDirection() {
        IndividualReport older = report2016();
        IndividualReport newer = report2026();
        when(userReportFacade.buildIndividualReportsListView("r@example.com"))
                .thenReturn(new UserReportsListViewModel(List.of(older, newer)));

        Optional<IndividualReport> found = facade.findCompatibleReport("r@example.com", older);

        assertTrue(found.isPresent());
        assertEquals("report-2026", found.get().getId());
    }

    @Test
    void findCompatibleReportIsEmptyWhenTheOtherSideIsNotAssigned() {
        IndividualReport newer = report2026();
        when(userReportFacade.buildIndividualReportsListView("r@example.com"))
                .thenReturn(new UserReportsListViewModel(List.of(newer)));

        assertTrue(facade.findCompatibleReport("r@example.com", newer).isEmpty());
    }

    @Test
    void findCompatibleReportIsEmptyForUnregisteredReportTypeKeys() {
        IndividualReport report = report2016();
        report.setReportTypeKey("matematica-2016"); // no configured pair

        assertTrue(facade.findCompatibleReport("r@example.com", report).isEmpty());
    }

    @Test
    void buildComparisonMatchesCriteriaByNameAndComputesDeltas() {
        User researcher = researcherAt(Position.CONF_UNIV);
        IndividualReport older = report2016();
        IndividualReport newer = report2026();

        // Older: B=30 (below 32 threshold), C=50, D=36 (meets 36 threshold exactly)
        when(userIndividualReportRunService.findLatestRun(researcher.getEmail(), "report-2016"))
                .thenReturn(Optional.of(run("report-2016", Map.of(0, 30.0, 1, 50.0, 2, 36.0))));
        // Newer: B=40 (now meets), C=45, D=36, plus new "Publicații A*+A"=12
        when(userIndividualReportRunService.findLatestRun(researcher.getEmail(), "report-2026"))
                .thenReturn(Optional.of(run("report-2026", Map.of(0, 40.0, 1, 45.0, 2, 36.0, 3, 12.0))));

        ReportComparisonViewModel comparison = facade.buildComparison(researcher, newer, older);

        assertEquals("report-2016", comparison.olderReport().getId());
        assertEquals("report-2026", comparison.newerReport().getId());
        assertTrue(comparison.olderRunAvailable());
        assertTrue(comparison.newerRunAvailable());

        assertEquals(4, comparison.rows().size());

        CriterionComparisonRow perspectivaB = comparison.rows().get(0);
        assertEquals("Perspectiva B", perspectivaB.name());
        assertEquals(30.0, perspectivaB.olderScore());
        assertEquals(40.0, perspectivaB.newerScore());
        assertEquals(10.0, perspectivaB.delta());
        assertEquals(100.0 / 3.0, perspectivaB.deltaPercent(), 0.0001); // 10/30 * 100
        assertFalse(perspectivaB.olderMet()); // 30 < 32
        assertTrue(perspectivaB.newerMet());  // 40 >= 32

        CriterionComparisonRow perspectivaC = comparison.rows().get(1);
        assertEquals(-5.0, perspectivaC.delta()); // 45 - 50
        assertEquals(-10.0, perspectivaC.deltaPercent(), 0.0001); // -5/50 * 100

        CriterionComparisonRow newOnly = comparison.rows().get(3);
        assertEquals("Publicații A*+A", newOnly.name());
        assertFalse(newOnly.presentInOlder());
        assertTrue(newOnly.presentInNewer());
        assertNull(newOnly.olderScore());
        assertNull(newOnly.delta());
        assertNull(newOnly.deltaPercent());

        // Total: sum of contributesToTotal criteria (B+C+D) on each side.
        assertEquals(30.0 + 50.0 + 36.0, comparison.olderTotalScore());
        assertEquals(40.0 + 45.0 + 36.0, comparison.newerTotalScore());
        assertEquals((40.0 + 45.0 + 36.0) - (30.0 + 50.0 + 36.0), comparison.totalScoreDelta());
        assertEquals(5.0 / 116.0 * 100.0, comparison.totalScoreDeltaPercent(), 0.0001);
    }

    @Test
    void percentDeltaIsNullWhenOlderScoreIsZero() {
        assertNull(ReportComparisonFacade.percentDelta(5.0, 0.0));
    }

    @Test
    void percentDeltaUsesAbsoluteOlderScoreAsDenominator() {
        // A hypothetical negative-older-score guard: percent stays finite/signed correctly even if
        // olderScore were negative (defensive — scores in this domain are never negative in practice).
        assertEquals(-50.0, ReportComparisonFacade.percentDelta(-5.0, 10.0), 0.0001);
    }

    @Test
    void buildComparisonReportsWhichSideIsMissingARunRatherThanThrowing() {
        User researcher = researcherAt(Position.CONF_UNIV);
        IndividualReport older = report2016();
        IndividualReport newer = report2026();

        when(userIndividualReportRunService.findLatestRun(researcher.getEmail(), "report-2016"))
                .thenReturn(Optional.empty());
        when(userIndividualReportRunService.findLatestRun(researcher.getEmail(), "report-2026"))
                .thenReturn(Optional.of(run("report-2026", Map.of())));

        ReportComparisonViewModel comparison = facade.buildComparison(researcher, newer, older);

        assertFalse(comparison.olderRunAvailable());
        assertTrue(comparison.newerRunAvailable());
        assertTrue(comparison.rows().isEmpty());
        assertNull(comparison.totalScoreDelta());
    }

    private User researcherAt(Position position) {
        User u = new User();
        u.setEmail("r@example.com");
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setPosition(position);
        u.setResearcherProfile(profile);
        return u;
    }

    private IndividualReportRunDto run(String reportId, Map<Integer, Double> criteriaScores) {
        return new IndividualReportRunDto(
                "run-" + reportId, reportId, List.of(), Map.of(), criteriaScores,
                java.time.Instant.now(), IndividualReportRunDto.Source.PERSISTED, "r@example.com");
    }
}
