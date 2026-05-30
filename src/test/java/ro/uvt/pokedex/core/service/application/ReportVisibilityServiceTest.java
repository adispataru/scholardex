package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentReportHide;
import ro.uvt.pokedex.core.model.org.DivisionReportSelection;
import ro.uvt.pokedex.core.model.org.Membership;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.org.DepartmentReportHideRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.DivisionReportSelectionRepository;
import ro.uvt.pokedex.core.repository.org.MembershipRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportVisibilityServiceTest {

    @Mock private DivisionReportSelectionRepository divisionReportSelectionRepository;
    @Mock private DepartmentReportHideRepository departmentReportHideRepository;
    @Mock private IndividualReportRepository individualReportRepository;
    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private MembershipRepository membershipRepository;

    @InjectMocks
    private ReportVisibilityService service;

    @BeforeEach
    void defaultStubs() {
        lenient().when(divisionReportSelectionRepository.findByDivisionId(any())).thenReturn(List.of());
        lenient().when(divisionReportSelectionRepository.findByDivisionIdIn(any())).thenReturn(List.of());
        lenient().when(departmentReportHideRepository.findByDepartmentId(any())).thenReturn(List.of());
        lenient().when(departmentReportHideRepository.findByDepartmentIdIn(any())).thenReturn(List.of());
        lenient().when(individualReportRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<String> ids = inv.getArgument(0);
            List<IndividualReport> out = new ArrayList<>();
            for (String id : ids) {
                IndividualReport r = new IndividualReport();
                r.setId(id);
                r.setTitle("Report " + id);
                out.add(r);
            }
            return out;
        });
    }

    // --- Visibility resolution ---

    @Test
    void userWithoutMembershipsSeesNoReports() {
        when(membershipRepository.findByUserIdAndValidToIsNull("u@uvt.ro")).thenReturn(List.of());
        assertTrue(service.listVisibleReportsForUser("u@uvt.ro").isEmpty());
        assertTrue(service.listVisibleReportsForUser(null).isEmpty());
        assertTrue(service.listVisibleReportsForUser("  ").isEmpty());
    }

    @Test
    void reportSelectedInDivisionFlowsThroughToUserInThatGroup() {
        when(membershipRepository.findByUserIdAndValidToIsNull("u@uvt.ro"))
                .thenReturn(List.of(membership("u@uvt.ro", "g1")));
        when(groupRepository.findAllById(Set.of("g1"))).thenReturn(List.of(group("g1", List.of("dept-cs"))));
        when(departmentRepository.findByIdIn(List.of("dept-cs"))).thenReturn(List.of(department("dept-cs", "div-fmi")));
        when(divisionReportSelectionRepository.findByDivisionIdIn(any()))
                .thenReturn(List.of(selection("div-fmi", "rep-A")));

        List<IndividualReport> visible = service.listVisibleReportsForUser("u@uvt.ro");
        assertEquals(List.of("rep-A"), visible.stream().map(IndividualReport::getId).toList());
    }

    @Test
    void departmentLevelHideRemovesReportEvenWhenSelectedAtDivision() {
        when(membershipRepository.findByUserIdAndValidToIsNull("u@uvt.ro"))
                .thenReturn(List.of(membership("u@uvt.ro", "g1")));
        when(groupRepository.findAllById(Set.of("g1"))).thenReturn(List.of(group("g1", List.of("dept-cs"))));
        when(departmentRepository.findByIdIn(List.of("dept-cs"))).thenReturn(List.of(department("dept-cs", "div-fmi")));
        when(divisionReportSelectionRepository.findByDivisionIdIn(any()))
                .thenReturn(List.of(selection("div-fmi", "rep-A"), selection("div-fmi", "rep-B")));
        when(departmentReportHideRepository.findByDepartmentIdIn(List.of("dept-cs")))
                .thenReturn(List.of(hide("dept-cs", "rep-B")));

        List<IndividualReport> visible = service.listVisibleReportsForUser("u@uvt.ro");
        assertEquals(List.of("rep-A"), visible.stream().map(IndividualReport::getId).toList());
    }

    @Test
    void reportVisibleViaSecondGroupEvenWhenHiddenInFirstGroupsDepartment() {
        // The user is in two groups, one under dept-cs (where rep-A is hidden) and one under
        // dept-math (where it isn't). The union semantics still surface rep-A.
        when(membershipRepository.findByUserIdAndValidToIsNull("u@uvt.ro"))
                .thenReturn(List.of(membership("u@uvt.ro", "g-cs"), membership("u@uvt.ro", "g-math")));
        when(groupRepository.findAllById(any())).thenReturn(List.of(
                group("g-cs", List.of("dept-cs")),
                group("g-math", List.of("dept-math"))));
        when(departmentRepository.findByIdIn(List.of("dept-cs")))
                .thenReturn(List.of(department("dept-cs", "div-fmi")));
        when(departmentRepository.findByIdIn(List.of("dept-math")))
                .thenReturn(List.of(department("dept-math", "div-fmi")));
        when(divisionReportSelectionRepository.findByDivisionIdIn(any()))
                .thenReturn(List.of(selection("div-fmi", "rep-A")));
        when(departmentReportHideRepository.findByDepartmentIdIn(List.of("dept-cs")))
                .thenReturn(List.of(hide("dept-cs", "rep-A")));
        when(departmentReportHideRepository.findByDepartmentIdIn(List.of("dept-math")))
                .thenReturn(List.of());

        List<IndividualReport> visible = service.listVisibleReportsForUser("u@uvt.ro");
        assertEquals(List.of("rep-A"), visible.stream().map(IndividualReport::getId).toList());
    }

    @Test
    void listVisibleReportsForDepartmentAppliesHidesOnTopOfInheritance() {
        when(departmentRepository.findById("dept-cs"))
                .thenReturn(Optional.of(department("dept-cs", "div-fmi")));
        when(divisionReportSelectionRepository.findByDivisionId("div-fmi"))
                .thenReturn(List.of(selection("div-fmi", "rep-A"), selection("div-fmi", "rep-B")));
        when(departmentReportHideRepository.findByDepartmentId("dept-cs"))
                .thenReturn(List.of(hide("dept-cs", "rep-B")));

        List<IndividualReport> visible = service.listVisibleReportsForDepartment("dept-cs");
        assertEquals(List.of("rep-A"), visible.stream().map(IndividualReport::getId).toList());
    }

    @Test
    void buildDepartmentVisibilityViewReturnsInheritedPlusHiddenSet() {
        when(departmentRepository.findById("dept-cs"))
                .thenReturn(Optional.of(department("dept-cs", "div-fmi")));
        when(divisionReportSelectionRepository.findByDivisionId("div-fmi"))
                .thenReturn(List.of(selection("div-fmi", "rep-A"), selection("div-fmi", "rep-B")));
        when(departmentReportHideRepository.findByDepartmentId("dept-cs"))
                .thenReturn(List.of(hide("dept-cs", "rep-B")));

        ReportVisibilityService.DepartmentVisibilityView view =
                service.buildDepartmentVisibilityView("dept-cs");

        assertEquals(2, view.inheritedReports().size());
        assertEquals(Set.of("rep-B"), view.hiddenReportIds());
    }

    // --- Access control on mutations ---

    @Test
    void divisionSelectionRefusedForNonHead() {
        OrgDivision div = orgDivision("div-fmi", List.of("dean@uvt.ro"));
        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(div));
        Authentication wrong = auth("bystander@uvt.ro", UserRole.SUPERVISOR.name());

        assertThrows(AccessDeniedException.class,
                () -> service.setDivisionSelection("div-fmi", "rep-A", true, wrong));
        verify(divisionReportSelectionRepository, never()).save(any());
    }

    @Test
    void divisionSelectionAllowedForHeadAndPersists() {
        OrgDivision div = orgDivision("div-fmi", List.of("dean@uvt.ro"));
        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(div));
        when(divisionReportSelectionRepository.findByDivisionIdAndReportId("div-fmi", "rep-A"))
                .thenReturn(Optional.empty());

        service.setDivisionSelection("div-fmi", "rep-A", true, auth("dean@uvt.ro", UserRole.SUPERVISOR.name()));

        verify(divisionReportSelectionRepository).save(any(DivisionReportSelection.class));
    }

    @Test
    void platformAdminMayManageAnyDivisionSelection() {
        OrgDivision div = orgDivision("div-fmi", List.of()); // no heads at all
        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(div));
        when(divisionReportSelectionRepository.findByDivisionIdAndReportId("div-fmi", "rep-A"))
                .thenReturn(Optional.empty());

        service.setDivisionSelection("div-fmi", "rep-A", true,
                auth("admin@uvt.ro", UserRole.PLATFORM_ADMIN.name()));

        verify(divisionReportSelectionRepository).save(any(DivisionReportSelection.class));
    }

    @Test
    void deselectingDivisionSelectionDeletesAndDoesNotSave() {
        OrgDivision div = orgDivision("div-fmi", List.of("dean@uvt.ro"));
        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(div));

        service.setDivisionSelection("div-fmi", "rep-A", false,
                auth("dean@uvt.ro", UserRole.SUPERVISOR.name()));

        verify(divisionReportSelectionRepository).deleteByDivisionIdAndReportId("div-fmi", "rep-A");
        verify(divisionReportSelectionRepository, never()).save(any());
    }

    @Test
    void departmentHideAllowedForDepartmentHead() {
        Department dept = department("dept-cs", "div-fmi");
        dept.setHeadUserIds(new ArrayList<>(List.of("dept-head@uvt.ro")));
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(dept));
        when(departmentReportHideRepository.findByDepartmentIdAndReportId("dept-cs", "rep-A"))
                .thenReturn(Optional.empty());

        service.setDepartmentHide("dept-cs", "rep-A", true,
                auth("dept-head@uvt.ro", UserRole.SUPERVISOR.name()));

        verify(departmentReportHideRepository).save(any(DepartmentReportHide.class));
    }

    @Test
    void departmentHideAllowedForParentDivisionHead() {
        Department dept = department("dept-cs", "div-fmi"); // no department heads at all
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(dept));
        when(orgDivisionRepository.findById("div-fmi"))
                .thenReturn(Optional.of(orgDivision("div-fmi", List.of("dean@uvt.ro"))));
        when(departmentReportHideRepository.findByDepartmentIdAndReportId("dept-cs", "rep-A"))
                .thenReturn(Optional.empty());

        service.setDepartmentHide("dept-cs", "rep-A", true,
                auth("dean@uvt.ro", UserRole.SUPERVISOR.name()));

        verify(departmentReportHideRepository).save(any(DepartmentReportHide.class));
    }

    @Test
    void departmentHideRefusedForOutsider() {
        Department dept = department("dept-cs", "div-fmi");
        dept.setHeadUserIds(new ArrayList<>(List.of("dept-head@uvt.ro")));
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(dept));
        when(orgDivisionRepository.findById("div-fmi"))
                .thenReturn(Optional.of(orgDivision("div-fmi", List.of("dean@uvt.ro"))));

        assertThrows(AccessDeniedException.class,
                () -> service.setDepartmentHide("dept-cs", "rep-A", true,
                        auth("bystander@uvt.ro", UserRole.SUPERVISOR.name())));
        verify(departmentReportHideRepository, never()).save(any());
    }

    // --- helpers ---

    private static Authentication auth(String userId, String authority) {
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority(authority));
        return new UsernamePasswordAuthenticationToken(userId, "n/a", auths);
    }

    private static Membership membership(String userId, String groupId) {
        Membership m = new Membership();
        m.setUserId(userId);
        m.setGroupId(groupId);
        return m;
    }

    private static Group group(String id, List<String> deptIds) {
        Group g = new Group();
        g.setId(id);
        g.setDepartmentIds(new ArrayList<>(deptIds));
        return g;
    }

    private static Department department(String id, String divisionId) {
        Department d = new Department();
        d.setId(id);
        d.setDivisionId(divisionId);
        return d;
    }

    private static OrgDivision orgDivision(String id, List<String> heads) {
        OrgDivision d = new OrgDivision();
        d.setId(id);
        d.setHeadUserIds(new ArrayList<>(heads));
        return d;
    }

    private static DivisionReportSelection selection(String divisionId, String reportId) {
        DivisionReportSelection s = new DivisionReportSelection();
        s.setDivisionId(divisionId);
        s.setReportId(reportId);
        return s;
    }

    private static DepartmentReportHide hide(String departmentId, String reportId) {
        DepartmentReportHide h = new DepartmentReportHide();
        h.setDepartmentId(departmentId);
        h.setReportId(reportId);
        return h;
    }
}
