package ro.uvt.pokedex.core.service.application.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.reporting.ReportingDataEpoch;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.OrgUnitRosterService;
import ro.uvt.pokedex.core.service.application.ReportingDataEpochService;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgUnitRunRollupServiceTest {

    @Mock private UserIndividualReportRunRepository runRepository;
    @Mock private ReportingDataEpochService reportingDataEpochService;

    @InjectMocks
    private OrgUnitRunRollupService rollupService;

    @Test
    void rollupTakesEachMembersLatestRunAndCountsMembersWithoutOne() {
        IndividualReport report = report("rep-1");
        when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());
        UserIndividualReportRun anaRun = run("run-ana", "ana@uvt.ro",
                Instant.parse("2026-07-01T10:00:00Z"), Map.of(0, 4.0), false);
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(anaRun));
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("dan@uvt.ro", "rep-1"))
                .thenReturn(Optional.empty());

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(
                List.of(member("ana@uvt.ro", "Ana"), member("dan@uvt.ro", "Dan")), report);

        assertEquals(2, rollup.rows().size());
        assertEquals("run-ana", rollup.rows().get(0).current().runId());
        assertEquals(Map.of(0, 4.0), rollup.rows().get(0).current().criteriaScores());
        assertNull(rollup.rows().get(1).current());
        assertEquals(1, rollup.membersWithoutRun());
        assertEquals(0, rollup.staleCount());
        assertEquals(Instant.parse("2026-07-01T10:00:00Z"), rollup.oldestRunAt());
    }

    @Test
    void runsOlderThanTheEpochBumpAreStale() {
        IndividualReport report = report("rep-1");
        ReportingDataEpoch epoch = new ReportingDataEpoch();
        epoch.setEpoch(3);
        epoch.setUpdatedAt(Instant.parse("2026-07-01T00:00:00Z"));
        epoch.setLastReason("WoS projection rebuild");
        when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.of(epoch));

        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("old@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-old", "old@uvt.ro",
                        Instant.parse("2026-06-15T10:00:00Z"), Map.of(), false)));
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("new@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-new", "new@uvt.ro",
                        Instant.parse("2026-07-02T10:00:00Z"), Map.of(), false)));

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(
                List.of(member("old@uvt.ro", "Old"), member("new@uvt.ro", "New")), report);

        assertTrue(rollup.rows().get(0).stale());
        assertFalse(rollup.rows().get(1).stale());
        assertEquals(1, rollup.staleCount());
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), rollup.epochUpdatedAt());
        assertEquals("WoS projection rebuild", rollup.epochLastReason());
    }

    @Test
    void missingEpochDocumentMarksNothingStale() {
        IndividualReport report = report("rep-1");
        when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-ana", "ana@uvt.ro",
                        Instant.parse("2020-01-01T00:00:00Z"), Map.of(), false)));

        OrgUnitRunRollupService.OrgUnitRunRollup rollup =
                rollupService.rollup(List.of(member("ana@uvt.ro", "Ana")), report);

        assertFalse(rollup.rows().get(0).stale());
        assertNull(rollup.epochUpdatedAt());
    }

    @Test
    void provisionalRunsAreCountedAndFlagged() {
        IndividualReport report = report("rep-1");
        when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-ana", "ana@uvt.ro",
                        Instant.parse("2026-07-01T10:00:00Z"), Map.of(0, 2.0), true)));

        OrgUnitRunRollupService.OrgUnitRunRollup rollup =
                rollupService.rollup(List.of(member("ana@uvt.ro", "Ana")), report);

        assertTrue(rollup.rows().get(0).current().provisional());
        assertEquals(1, rollup.provisionalCount());
    }

    @Test
    void totalSumsOnlyCriteriaThatContributeToTotalAndIsNullWhenNoneDo() {
        IndividualReport report = report("rep-1");
        AbstractReport.Criterion c0 = new AbstractReport.Criterion();
        c0.setName("Articles");
        c0.setContributesToTotal(true);
        AbstractReport.Criterion c1 = new AbstractReport.Criterion();
        c1.setName("Citations");
        AbstractReport.Criterion c2 = new AbstractReport.Criterion();
        c2.setName("Grants");
        c2.setContributesToTotal(true);
        report.setCriteria(List.of(c0, c1, c2));

        when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-ana", "ana@uvt.ro",
                        Instant.parse("2026-07-01T10:00:00Z"), Map.of(0, 4.0, 1, 100.0, 2, 1.5), false)));

        OrgUnitRunRollupService.OrgUnitRunRollup rollup =
                rollupService.rollup(List.of(member("ana@uvt.ro", "Ana")), report);
        assertEquals(5.5, rollup.rows().get(0).current().total());

        // No criterion contributes → total is null (matches the workspace rule).
        IndividualReport noTotals = report("rep-2");
        noTotals.setCriteria(List.of(c1));
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-2"))
                .thenReturn(Optional.of(run("run-b", "ana@uvt.ro",
                        Instant.parse("2026-07-01T10:00:00Z"), Map.of(0, 100.0), false)));
        OrgUnitRunRollupService.OrgUnitRunRollup rollup2 =
                rollupService.rollup(List.of(member("ana@uvt.ro", "Ana")), noTotals);
        assertNull(rollup2.rows().get(0).current().total());
    }

    @Test
    void criteriaThresholdsComeFromTheReportDefinition() {
        IndividualReport report = report("rep-1");
        AbstractReport.Criterion c0 = new AbstractReport.Criterion();
        c0.setName("Articles");
        AbstractReport.Threshold prof = new AbstractReport.Threshold();
        prof.setPosition(Position.PROF_UNIV);
        prof.setValue(5.0);
        AbstractReport.Threshold conf = new AbstractReport.Threshold();
        conf.setPosition(Position.CONF_UNIV);
        conf.setValue(2.5);
        c0.setThresholds(List.of(prof, conf));
        report.setCriteria(List.of(c0));
        when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());

        OrgUnitRunRollupService.OrgUnitRunRollup rollup = rollupService.rollup(List.of(), report);

        assertEquals(Map.of("PROF_UNIV", 5.0, "CONF_UNIV", 2.5), rollup.criteriaThresholds().get(0));
    }


    private static OrgUnitRosterService.RosterMember member(String email, String firstName) {
        return memberWithLabel(email, firstName, "", "");
    }

    private static OrgUnitRosterService.RosterMember memberWithLabel(
            String email, String firstName, String lastName, String label) {
        User u = new User();
        u.setEmail(email);
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(firstName);
        p.setLastName(lastName);
        u.setResearcherProfile(p);
        return new OrgUnitRosterService.RosterMember(u, label);
    }

    private static UserIndividualReportRun run(String id, String email, Instant createdAt,
                                               Map<Integer, Double> criteriaScores, boolean provisional) {
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setId(id);
        run.setUserEmail(email);
        run.setCreatedAt(createdAt);
        run.setCriteriaScores(new java.util.HashMap<>(criteriaScores));
        run.setProvisional(provisional);
        run.setStatus(UserIndividualReportRun.Status.READY);
        return run;
    }

    private static IndividualReport report(String id) {
        IndividualReport r = new IndividualReport();
        r.setId(id);
        r.setTitle("Test report");
        return r;
    }
}
