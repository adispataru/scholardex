package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.model.reporting.ReportingDataEpoch;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.OrgUnitReportRefreshEventRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgUnitReportRefreshServiceTest {

    @Mock private OrgUnitRosterService orgUnitRosterService;
    @Mock private IndividualReportRepository individualReportRepository;
    @Mock private UserIndividualReportRunRepository userIndividualReportRunRepository;
    @Mock private UserIndividualReportRunService userIndividualReportRunService;
    @Mock private ReportingDataEpochService reportingDataEpochService;
    @Mock private OrgUnitReportRefreshEventRepository orgUnitReportRefreshEventRepository;

    @InjectMocks
    private OrgUnitReportRefreshService refreshService;

    private IndividualReport report;

    @BeforeEach
    void setUp() {
        report = new IndividualReport();
        report.setId("rep-1");
        lenient().when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        lenient().when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());
    }

    @Test
    void staleScopeRefreshesMissingAndStaleRunsAndSkipsFreshOnes() {
        ReportingDataEpoch epoch = new ReportingDataEpoch();
        epoch.setUpdatedAt(Instant.parse("2026-07-01T00:00:00Z"));
        when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.of(epoch));
        when(orgUnitRosterService.departmentRoster("dept-cs")).thenReturn(List.of(
                member("norun@uvt.ro"), member("stale@uvt.ro"), member("fresh@uvt.ro")));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("norun@uvt.ro", "rep-1"))
                .thenReturn(Optional.empty());
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("stale@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run(Instant.parse("2026-06-01T00:00:00Z"), false)));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("fresh@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run(Instant.parse("2026-07-02T00:00:00Z"), false)));

        OrgUnitReportRefreshService.RefreshAllResult result = refreshService.refreshAll(
                OrgUnitReportRefreshEvent.UnitType.DEPARTMENT, "dept-cs", "rep-1",
                OrgUnitReportRefreshService.Scope.STALE, null, "admin@uvt.ro");

        assertEquals(2, result.refreshed());
        assertEquals(1, result.skippedFresh());
        assertEquals(0, result.failed());
        verify(userIndividualReportRunService).refreshRunWithAllIndicators("norun@uvt.ro", "rep-1", "admin@uvt.ro");
        verify(userIndividualReportRunService).refreshRunWithAllIndicators("stale@uvt.ro", "rep-1", "admin@uvt.ro");
        verify(userIndividualReportRunService, never())
                .refreshRunWithAllIndicators(eq("fresh@uvt.ro"), any(), any());
    }

    @Test
    void allScopeRefreshesFreshRunsToo() {
        ReportingDataEpoch epoch = new ReportingDataEpoch();
        epoch.setUpdatedAt(Instant.parse("2026-07-01T00:00:00Z"));
        when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.of(epoch));
        when(orgUnitRosterService.departmentRoster("dept-cs")).thenReturn(List.of(member("fresh@uvt.ro")));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("fresh@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run(Instant.parse("2026-07-02T00:00:00Z"), false)));

        OrgUnitReportRefreshService.RefreshAllResult result = refreshService.refreshAll(
                OrgUnitReportRefreshEvent.UnitType.DEPARTMENT, "dept-cs", "rep-1",
                OrgUnitReportRefreshService.Scope.ALL, null, "admin@uvt.ro");

        assertEquals(1, result.refreshed());
        assertEquals(0, result.skippedFresh());
    }

    @Test
    void provisionalLatestRunsAreNeverRefreshed() {
        when(orgUnitRosterService.departmentRoster("dept-cs")).thenReturn(List.of(member("prov@uvt.ro")));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("prov@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run(Instant.parse("2026-07-02T00:00:00Z"), true)));

        OrgUnitReportRefreshService.RefreshAllResult result = refreshService.refreshAll(
                OrgUnitReportRefreshEvent.UnitType.DEPARTMENT, "dept-cs", "rep-1",
                OrgUnitReportRefreshService.Scope.ALL, null, "admin@uvt.ro");

        assertEquals(1, result.skippedProvisional());
        assertEquals(0, result.refreshed());
        verify(userIndividualReportRunService, never()).refreshRunWithAllIndicators(any(), any(), any());
    }

    @Test
    void aFailingMemberDoesNotStopTheBatch() {
        when(orgUnitRosterService.departmentRoster("dept-cs")).thenReturn(List.of(
                member("boom@uvt.ro"), member("ok@uvt.ro")));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(any(), eq("rep-1")))
                .thenReturn(Optional.empty());
        when(userIndividualReportRunService.refreshRunWithAllIndicators("boom@uvt.ro", "rep-1", "admin@uvt.ro"))
                .thenThrow(new RuntimeException("scoring exploded"));

        OrgUnitReportRefreshService.RefreshAllResult result = refreshService.refreshAll(
                OrgUnitReportRefreshEvent.UnitType.DEPARTMENT, "dept-cs", "rep-1",
                OrgUnitReportRefreshService.Scope.ALL, null, "admin@uvt.ro");

        assertEquals(1, result.failed());
        assertEquals(1, result.refreshed());
        verify(userIndividualReportRunService).refreshRunWithAllIndicators("ok@uvt.ro", "rep-1", "admin@uvt.ro");
    }

    @Test
    void everyBatchPersistsAnAuditEventWithTheCounts() {
        when(orgUnitRosterService.divisionRoster("div-fmi")).thenReturn(List.of(member("ana@uvt.ro")));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.empty());

        refreshService.refreshAll(OrgUnitReportRefreshEvent.UnitType.DIVISION, "div-fmi", "rep-1",
                OrgUnitReportRefreshService.Scope.STALE, "  before evaluation  ", "admin@uvt.ro");

        ArgumentCaptor<OrgUnitReportRefreshEvent> captor = ArgumentCaptor.forClass(OrgUnitReportRefreshEvent.class);
        verify(orgUnitReportRefreshEventRepository).save(captor.capture());
        OrgUnitReportRefreshEvent event = captor.getValue();
        assertEquals(OrgUnitReportRefreshEvent.UnitType.DIVISION, event.getUnitType());
        assertEquals("div-fmi", event.getUnitId());
        assertEquals("rep-1", event.getReportDefinitionId());
        assertEquals("admin@uvt.ro", event.getTriggeredByEmail());
        assertEquals("before evaluation", event.getLabel());
        assertEquals(1, event.getRefreshed());
        assertEquals(1, event.getRosterSize());
        assertNotNull(event.getCreatedAt());
    }

    @Test
    void unknownReportFailsFastWithoutTouchingTheRoster() {
        when(individualReportRepository.findById("nope")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> refreshService.refreshAll(
                OrgUnitReportRefreshEvent.UnitType.DEPARTMENT, "dept-cs", "nope",
                OrgUnitReportRefreshService.Scope.STALE, null, "admin@uvt.ro"));
        verify(orgUnitRosterService, never()).departmentRoster(any());
    }

    private static OrgUnitRosterService.RosterMember member(String email) {
        User u = new User();
        u.setEmail(email);
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(email.substring(0, email.indexOf('@')));
        p.setLastName("");
        u.setResearcherProfile(p);
        return new OrgUnitRosterService.RosterMember(u, "");
    }

    private static UserIndividualReportRun run(Instant createdAt, boolean provisional) {
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setId("run-" + createdAt);
        run.setCreatedAt(createdAt);
        run.setProvisional(provisional);
        run.setStatus(UserIndividualReportRun.Status.READY);
        return run;
    }
}
