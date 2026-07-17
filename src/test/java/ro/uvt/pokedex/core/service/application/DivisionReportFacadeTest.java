package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.OrgUnitReportRefreshEventRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitReportViewAssembler;
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
class DivisionReportFacadeTest {

    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private DepartmentAffiliationRepository departmentAffiliationRepository;
    @Mock private UserRepository userRepository;
    @Mock private IndividualReportRepository individualReportRepository;
    @Mock private UserIndividualReportRunRepository userIndividualReportRunRepository;
    @Mock private OrgUnitReportRefreshEventRepository orgUnitReportRefreshEventRepository;
    @Mock private ReportingDataEpochService reportingDataEpochService;
    @Mock private ReportVisibilityService reportVisibilityService;
    @Mock private GroupMembershipService groupMembershipService;

    private DivisionReportFacade facade;

    @BeforeEach
    void wireFacade() {
        // Real roster + rollup services over mocked repositories — the facade is just plumbing.
        OrgUnitRosterService rosterService = new OrgUnitRosterService(
                departmentRepository, departmentAffiliationRepository, userRepository, groupMembershipService);
        OrgUnitRunRollupService rollupService = new OrgUnitRunRollupService(
                userIndividualReportRunRepository, reportingDataEpochService);
        OrgUnitReportViewAssembler assembler = new OrgUnitReportViewAssembler(
                new com.fasterxml.jackson.databind.ObjectMapper());
        facade = new DivisionReportFacade(orgDivisionRepository, individualReportRepository,
                rosterService, rollupService, assembler, orgUnitReportRefreshEventRepository,
                reportVisibilityService,
                new ro.uvt.pokedex.core.service.application.reporting.PromotionReadinessService());
        lenient().when(reportingDataEpochService.currentEpochInfo()).thenReturn(Optional.empty());
        lenient().when(orgUnitReportRefreshEventRepository
                        .findTop20ByUnitTypeAndUnitIdAndReportDefinitionIdOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void buildViewAggregatesResearchersFromAllChildDepartmentsAndLabelsByOriginatingDepartment() {
        OrgDivision fmi = division("div-fmi", "FMI");
        Department cs = department("dept-cs", "Computer Science");
        Department math = department("dept-math", "Mathematics");
        IndividualReport report = report("rep-1");

        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(fmi));
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(departmentRepository.findByDivisionId("div-fmi")).thenReturn(List.of(cs, math));

        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of(affiliation("dept-cs", "ana@uvt.ro")));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-math"))
                .thenReturn(List.of(affiliation("dept-math", "ioana@uvt.ro")));

        when(userRepository.findAllById(any())).thenReturn(List.of(
                user("ana@uvt.ro", "Ana"),
                user("ioana@uvt.ro", "Ioana")));

        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-ana", "ana@uvt.ro", Map.of(0, 4.0))));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ioana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-ioana", "ioana@uvt.ro", Map.of(0, 6.0))));

        Optional<OrgUnitReportViewModel> view = facade.buildView("div-fmi", "rep-1", null);

        assertTrue(view.isPresent());
        OrgUnitReportViewModel vm = view.get();
        assertEquals("div-fmi", vm.unitId());
        assertEquals("FMI", vm.unitName());
        assertEquals(List.of("Ana", "Ioana"),
                vm.researchers().stream().map(u -> u.getResearcherProfile().getName().trim()).toList());
        assertEquals(Map.of(0, 4.0), vm.researcherScores().get("ana@uvt.ro"));
        assertEquals(Map.of(0, 6.0), vm.researcherScores().get("ioana@uvt.ro"));
        assertEquals("Computer Science", vm.departmentLabelByResearcher().get("ana@uvt.ro"));
        assertEquals("Mathematics", vm.departmentLabelByResearcher().get("ioana@uvt.ro"));
        assertEquals(0, vm.membersWithoutRun());
    }

    @Test
    void jointAppointmentsAreDedupedAndLabelCombinesAllDepartments() {
        OrgDivision fmi = division("div-fmi", "FMI");
        Department cs = department("dept-cs", "Computer Science");
        Department math = department("dept-math", "Mathematics");
        IndividualReport report = report("rep-1");

        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(fmi));
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(departmentRepository.findByDivisionId("div-fmi")).thenReturn(List.of(cs, math));

        // Ana is affiliated with BOTH departments (joint appointment).
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of(affiliation("dept-cs", "ana@uvt.ro")));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-math"))
                .thenReturn(List.of(affiliation("dept-math", "ana@uvt.ro")));

        when(userRepository.findAllById(any())).thenReturn(List.of(user("ana@uvt.ro", "Ana")));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("ana@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(run("run-ana", "ana@uvt.ro", Map.of(0, 4.0))));

        Optional<OrgUnitReportViewModel> view = facade.buildView("div-fmi", "rep-1", null);

        OrgUnitReportViewModel vm = view.orElseThrow();
        // Ana appears once
        assertEquals(1, vm.researchers().size());
        // Label combines both departments
        assertEquals("Computer Science + Mathematics", vm.departmentLabelByResearcher().get("ana@uvt.ro"));
    }

    @Test
    void membersWithoutARunShowNoScoresButStayInTheRoster() {
        OrgDivision fmi = division("div-fmi", "FMI");
        Department cs = department("dept-cs", "Computer Science");
        IndividualReport report = report("rep-1");

        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(fmi));
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(departmentRepository.findByDivisionId("div-fmi")).thenReturn(List.of(cs));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of(affiliation("dept-cs", "dan@uvt.ro")));
        when(userRepository.findAllById(any())).thenReturn(List.of(user("dan@uvt.ro", "Dan")));
        when(userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("dan@uvt.ro", "rep-1"))
                .thenReturn(Optional.empty());

        OrgUnitReportViewModel vm = facade.buildView("div-fmi", "rep-1", null).orElseThrow();

        assertEquals(1, vm.researchers().size());
        assertTrue(vm.researcherScores().isEmpty());
        assertTrue(vm.runMetaByEmail().isEmpty());
        assertEquals(1, vm.membersWithoutRun());
    }

    @Test
    void emptyDivisionStillProducesViewWithNoResearchers() {
        OrgDivision empty = division("div-empty", "Empty");
        IndividualReport report = report("rep-1");

        when(orgDivisionRepository.findById("div-empty")).thenReturn(Optional.of(empty));
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(departmentRepository.findByDivisionId("div-empty")).thenReturn(List.of());

        OrgUnitReportViewModel vm = facade.buildView("div-empty", "rep-1", null).orElseThrow();
        assertTrue(vm.researchers().isEmpty());
        assertTrue(vm.researcherScores().isEmpty());
    }

    private static OrgDivision division(String id, String name) {
        OrgDivision d = new OrgDivision();
        d.setId(id);
        d.setName(name);
        return d;
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
