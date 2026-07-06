package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.OrgUnitReportRefreshEventRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepartmentReportFacadeTest {

    @Mock private DepartmentRepository departmentRepository;
    @Mock private DepartmentAffiliationRepository departmentAffiliationRepository;
    @Mock private UserRepository userRepository;
    @Mock private IndividualReportRepository individualReportRepository;
    @Mock private UserIndividualReportRunRepository userIndividualReportRunRepository;
    @Mock private OrgUnitReportRefreshEventRepository orgUnitReportRefreshEventRepository;
    @Mock private ReportingDataEpochService reportingDataEpochService;
    @Mock private ReportVisibilityService reportVisibilityService;

    private DepartmentReportFacade facade;

    @BeforeEach
    void wireFacade() {
        // Real roster + rollup services over mocked repositories — the facade is just plumbing.
        OrgUnitRosterService rosterService = new OrgUnitRosterService(
                departmentRepository, departmentAffiliationRepository, userRepository);
        OrgUnitRunRollupService rollupService = new OrgUnitRunRollupService(
                userIndividualReportRunRepository, reportingDataEpochService);
        facade = new DepartmentReportFacade(departmentRepository, individualReportRepository,
                rosterService, rollupService, orgUnitReportRefreshEventRepository, reportVisibilityService);
        lenient().when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());
        lenient().when(orgUnitReportRefreshEventRepository
                        .findTop20ByUnitTypeAndUnitIdAndReportDefinitionIdOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void buildViewReturnsEmptyWhenDepartmentMissing() {
        when(departmentRepository.findById("nope")).thenReturn(Optional.empty());
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report("rep-1")));

        assertTrue(facade.buildView("nope", "rep-1", null).isEmpty());
    }

    @Test
    void buildViewReturnsEmptyWhenReportMissing() {
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(department("dept-cs", "CS")));
        when(individualReportRepository.findById("nope")).thenReturn(Optional.empty());

        assertTrue(facade.buildView("dept-cs", "nope", null).isEmpty());
    }

    @Test
    void buildViewResolvesResearchersThroughCurrentAffiliationsAndReadsTheirLatestRuns() {
        Department cs = department("dept-cs", "Computer Science");
        IndividualReport report = report("rep-1");
        User ana = user("ana@uvt.ro", "Ana");
        User dan = user("dan@uvt.ro", "Dan");

        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(cs));
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of(affiliation("dept-cs", "ana@uvt.ro"), affiliation("dept-cs", "dan@uvt.ro")));
        when(userRepository.findAllById(any())).thenReturn(List.of(ana, dan));

        UserIndividualReportRun anaRun = run("run-ana", "ana@uvt.ro", Map.of(0, 8.0));
        anaRun.setBuildErrors(List.of("No authors found"));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(anaRun));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("dan@uvt.ro", "rep-1"))
                .thenReturn(Optional.empty());

        Optional<OrgUnitReportViewModel> view = facade.buildView("dept-cs", "rep-1", null);

        assertTrue(view.isPresent());
        OrgUnitReportViewModel vm = view.get();
        assertEquals("dept-cs", vm.unitId());
        assertEquals("Computer Science", vm.unitName());
        assertEquals(List.of("Ana", "Dan"),
                vm.researchers().stream().map(u -> u.getResearcherProfile().getName().trim()).toList());
        // Ana has a run; Dan doesn't — only Ana's email is in the score map.
        assertEquals(Map.of(0, 8.0), vm.researcherScores().get("ana@uvt.ro"));
        assertFalse(vm.researcherScores().containsKey("dan@uvt.ro"));
        // Per-member run errors are name-prefixed for the unit-level warning list.
        assertEquals(List.of("Ana: No authors found"), vm.buildErrors());
        assertEquals(1, vm.membersWithoutRun());
        // departmentLabelByResearcher stays empty for department views.
        assertTrue(vm.departmentLabelByResearcher().isEmpty());
    }

    @Test
    void buildViewReturnsEmptyResearcherListWhenNoAffiliationsExist() {
        Department cs = department("dept-cs", "CS");
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(cs));
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report("rep-1")));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of());

        Optional<OrgUnitReportViewModel> view = facade.buildView("dept-cs", "rep-1", null);

        assertTrue(view.isPresent());
        assertTrue(view.get().researchers().isEmpty());
    }

    private static Department department(String id, String name) {
        Department d = new Department();
        d.setId(id);
        d.setName(name);
        return d;
    }

    private static DepartmentAffiliation affiliation(String departmentId, String userId) {
        DepartmentAffiliation a = new DepartmentAffiliation();
        a.setDepartmentId(departmentId);
        a.setUserId(userId);
        return a;
    }

    private static User user(String email, String firstName) {
        User u = new User();
        u.setEmail(email);
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setFirstName(firstName);
        p.setLastName("");
        u.setResearcherProfile(p);
        return u;
    }

    private static IndividualReport report(String id) {
        IndividualReport r = new IndividualReport();
        r.setId(id);
        r.setTitle("Test report");
        return r;
    }

    private static UserIndividualReportRun run(String id, String email, Map<Integer, Double> criteriaScores) {
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setId(id);
        run.setUserEmail(email);
        run.setCreatedAt(Instant.parse("2026-07-01T10:00:00Z"));
        run.setCriteriaScores(new HashMap<>(criteriaScores));
        run.setStatus(UserIndividualReportRun.Status.READY);
        return run;
    }
}
