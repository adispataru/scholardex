package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.IndividualReportComputer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
    @Mock private IndividualReportComputer individualReportComputer;
    @Mock private ReportingLookupMemoization reportingLookupMemoization;

    @InjectMocks
    private DivisionReportFacade facade;

    @BeforeEach
    void passThroughMemoization() {
        lenient().when(reportingLookupMemoization.withRefreshScope(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
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

        when(individualReportComputer.compute(any(), any())).thenReturn(
                new IndividualReportComputer.Computation(
                        List.of(
                                new IndividualReportComputer.ResearcherScoreEntry("ana@uvt.ro", Map.of(0, 4.0)),
                                new IndividualReportComputer.ResearcherScoreEntry("ioana@uvt.ro", Map.of(0, 6.0))),
                        List.of(),
                        Map.of()));

        Optional<OrgUnitReportViewModel> view = facade.buildView("div-fmi", "rep-1");

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
        when(individualReportComputer.compute(any(), any())).thenReturn(
                new IndividualReportComputer.Computation(
                        List.of(new IndividualReportComputer.ResearcherScoreEntry("ana@uvt.ro", Map.of(0, 4.0))),
                        List.of(), Map.of()));

        Optional<OrgUnitReportViewModel> view = facade.buildView("div-fmi", "rep-1");

        OrgUnitReportViewModel vm = view.orElseThrow();
        // Ana appears once
        assertEquals(1, vm.researchers().size());
        // Label combines both departments
        assertEquals("Computer Science + Mathematics", vm.departmentLabelByResearcher().get("ana@uvt.ro"));
    }

    @Test
    void emptyDivisionStillProducesViewWithNoResearchers() {
        OrgDivision empty = division("div-empty", "Empty");
        IndividualReport report = report("rep-1");

        when(orgDivisionRepository.findById("div-empty")).thenReturn(Optional.of(empty));
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(departmentRepository.findByDivisionId("div-empty")).thenReturn(List.of());
        when(individualReportComputer.compute(any(), any())).thenReturn(
                new IndividualReportComputer.Computation(List.of(), List.of(), Map.of()));

        OrgUnitReportViewModel vm = facade.buildView("div-empty", "rep-1").orElseThrow();
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
}
