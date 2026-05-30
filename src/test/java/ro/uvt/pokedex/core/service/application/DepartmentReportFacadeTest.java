package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.IndividualReportComputer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepartmentReportFacadeTest {

    @Mock private DepartmentRepository departmentRepository;
    @Mock private DepartmentAffiliationRepository departmentAffiliationRepository;
    @Mock private UserRepository userRepository;
    @Mock private IndividualReportRepository individualReportRepository;
    @Mock private IndividualReportComputer individualReportComputer;
    @Mock private ReportingLookupMemoization reportingLookupMemoization;

    @InjectMocks
    private DepartmentReportFacade facade;

    @BeforeEach
    void passThroughMemoization() {
        // The memoization wrapper just runs the supplier in test contexts.
        lenient().when(reportingLookupMemoization.withRefreshScope(any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    }

    @Test
    void buildViewReturnsEmptyWhenDepartmentMissing() {
        when(departmentRepository.findById("nope")).thenReturn(Optional.empty());
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report("rep-1")));

        assertTrue(facade.buildView("nope", "rep-1").isEmpty());
    }

    @Test
    void buildViewReturnsEmptyWhenReportMissing() {
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(department("dept-cs", "CS")));
        when(individualReportRepository.findById("nope")).thenReturn(Optional.empty());

        assertTrue(facade.buildView("dept-cs", "nope").isEmpty());
    }

    @Test
    void buildViewResolvesResearchersThroughCurrentAffiliationsAndDelegatesToComputer() {
        Department cs = department("dept-cs", "Computer Science");
        IndividualReport report = report("rep-1");
        DepartmentAffiliation a1 = affiliation("dept-cs", "ana@uvt.ro");
        DepartmentAffiliation a2 = affiliation("dept-cs", "dan@uvt.ro");
        User ana = user("ana@uvt.ro", "Ana");
        User dan = user("dan@uvt.ro", "Dan");

        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(cs));
        when(individualReportRepository.findById("rep-1")).thenReturn(Optional.of(report));
        when(departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull("dept-cs"))
                .thenReturn(List.of(a1, a2));
        when(userRepository.findAllById(any())).thenReturn(List.of(ana, dan));
        when(individualReportComputer.compute(any(), eq(report))).thenReturn(
                new IndividualReportComputer.Computation(
                        List.of(new IndividualReportComputer.ResearcherScoreEntry("ana@uvt.ro", Map.of(0, 8.0))),
                        List.of("No authors found for Dan"),
                        Map.of(0, Map.of("PROF_UNIV", 5.0))));

        Optional<OrgUnitReportViewModel> view = facade.buildView("dept-cs", "rep-1");

        assertTrue(view.isPresent());
        OrgUnitReportViewModel vm = view.get();
        assertEquals("dept-cs", vm.unitId());
        assertEquals("Computer Science", vm.unitName());
        assertEquals(List.of("Ana", "Dan"),
                vm.researchers().stream().map(u -> u.getResearcherProfile().getName().trim()).toList());
        // Ana scored; Dan didn't — only Ana's email is in the score map.
        assertEquals(Map.of(0, 8.0), vm.researcherScores().get("ana@uvt.ro"));
        assertFalse(vm.researcherScores().containsKey("dan@uvt.ro"));
        assertEquals(List.of("No authors found for Dan"), vm.buildErrors());
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
        when(individualReportComputer.compute(any(), any())).thenReturn(
                new IndividualReportComputer.Computation(List.of(), List.of(), Map.of()));

        Optional<OrgUnitReportViewModel> view = facade.buildView("dept-cs", "rep-1");

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
}
