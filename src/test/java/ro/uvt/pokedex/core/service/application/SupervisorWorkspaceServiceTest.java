package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DivisionType;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupervisorWorkspaceServiceTest {

    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private InstitutionRepository institutionRepository;

    @InjectMocks
    private SupervisorWorkspaceService service;

    @BeforeEach
    void defaultStubs() {
        // Make every repo return an empty list by default; specific tests override what they exercise.
        lenient().when(orgDivisionRepository.findByHeadUserIdsContaining(anyString())).thenReturn(List.of());
        lenient().when(departmentRepository.findByHeadUserIdsContaining(anyString())).thenReturn(List.of());
        lenient().when(groupRepository.findBySupervisorUserIdsContaining(anyString())).thenReturn(List.of());
        lenient().when(departmentRepository.findByDivisionIdIn(any())).thenReturn(List.of());
        lenient().when(groupRepository.findByDepartmentIdsIn(any())).thenReturn(List.of());
        lenient().when(institutionRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(orgDivisionRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(departmentRepository.findAllById(any())).thenReturn(List.of());
    }

    @Test
    void blankUserIdYieldsEmptyView() {
        assertTrue(service.buildView(null).isEmpty());
        assertTrue(service.buildView("").isEmpty());
        assertTrue(service.buildView("  ").isEmpty());
    }

    @Test
    void noAssignmentsYieldsEmptyView() {
        SupervisorWorkspaceService.SupervisorWorkspaceView view = service.buildView("nobody@uvt.ro");
        assertTrue(view.isEmpty());
        assertTrue(view.divisions().isEmpty());
        assertTrue(view.departments().isEmpty());
        assertTrue(view.groups().isEmpty());
    }

    @Test
    void directDivisionHeadInheritsAllDescendantDepartmentsAndGroups() {
        OrgDivision fmi = division("div-fmi", "FMI", "inst-uvt", DivisionType.FACULTY);
        Department cs = department("dept-cs", "CS", "div-fmi", "inst-uvt");
        Department math = department("dept-math", "Math", "div-fmi", "inst-uvt");
        Group ml = group("g-ml", "ML lab", List.of("dept-cs"), "inst-uvt", List.of());
        Group mlx = group("g-mlx", "MLX lab", List.of("dept-math"), "inst-uvt", List.of());

        when(orgDivisionRepository.findByHeadUserIdsContaining("ana@uvt.ro")).thenReturn(List.of(fmi));
        when(departmentRepository.findByDivisionIdIn(any())).thenReturn(List.of(cs, math));
        when(groupRepository.findByDepartmentIdsIn(any())).thenReturn(List.of(ml, mlx));

        SupervisorWorkspaceService.SupervisorWorkspaceView view = service.buildView("ana@uvt.ro");

        assertEquals(List.of("FMI"), view.divisions().stream().map(OrgDivision::getName).toList());
        assertEquals(List.of("CS", "Math"), view.departments().stream().map(Department::getName).toList());
        assertEquals(List.of("ML lab", "MLX lab"), view.groups().stream().map(Group::getName).toList());
    }

    @Test
    void departmentHeadOnlySeesThatDepartmentAndItsGroups() {
        Department cs = department("dept-cs", "CS", "div-fmi", "inst-uvt");
        Group ml = group("g-ml", "ML lab", List.of("dept-cs"), "inst-uvt", List.of());

        when(departmentRepository.findByHeadUserIdsContaining("dan@uvt.ro")).thenReturn(List.of(cs));
        when(groupRepository.findByDepartmentIdsIn(any())).thenReturn(List.of(ml));

        SupervisorWorkspaceService.SupervisorWorkspaceView view = service.buildView("dan@uvt.ro");

        assertTrue(view.divisions().isEmpty(), "department-only head must not see any divisions");
        assertEquals(List.of("CS"), view.departments().stream().map(Department::getName).toList());
        assertEquals(List.of("ML lab"), view.groups().stream().map(Group::getName).toList());
    }

    @Test
    void explicitGroupSupervisorShowsUpEvenWithoutAnyHeadAssignment() {
        Group focused = group("g-focused", "Focused", List.of("dept-x"), "inst-uvt", List.of("ext@uvt.ro"));

        when(groupRepository.findBySupervisorUserIdsContaining("ext@uvt.ro")).thenReturn(List.of(focused));

        SupervisorWorkspaceService.SupervisorWorkspaceView view = service.buildView("ext@uvt.ro");

        assertTrue(view.divisions().isEmpty());
        assertTrue(view.departments().isEmpty());
        assertEquals(List.of("Focused"), view.groups().stream().map(Group::getName).toList());
    }

    @Test
    void groupSurfacedTwiceViaExplicitAndImplicitChainIsDedupedAndOrdered() {
        OrgDivision fmi = division("div-fmi", "FMI", "inst-uvt", DivisionType.FACULTY);
        Department cs = department("dept-cs", "CS", "div-fmi", "inst-uvt");
        Group alpha = group("g-a", "Alpha", List.of("dept-cs"), "inst-uvt", List.of("ana@uvt.ro"));
        Group beta = group("g-b", "Beta", List.of("dept-cs"), "inst-uvt", List.of());

        when(orgDivisionRepository.findByHeadUserIdsContaining("ana@uvt.ro")).thenReturn(List.of(fmi));
        when(departmentRepository.findByDivisionIdIn(any())).thenReturn(List.of(cs));
        when(groupRepository.findBySupervisorUserIdsContaining("ana@uvt.ro")).thenReturn(List.of(alpha));
        when(groupRepository.findByDepartmentIdsIn(any())).thenReturn(List.of(alpha, beta));

        SupervisorWorkspaceService.SupervisorWorkspaceView view = service.buildView("ana@uvt.ro");

        // Alpha must appear only once and groups should be sorted by name.
        assertEquals(List.of("Alpha", "Beta"), view.groups().stream().map(Group::getName).toList());
    }

    @Test
    void buildsInstitutionAndDivisionNameLookupsForTemplateUse() {
        Institution uvt = new Institution();
        uvt.setId("inst-uvt");
        uvt.setName("Universitatea de Vest");
        OrgDivision fmi = division("div-fmi", "FMI", "inst-uvt", DivisionType.FACULTY);
        Department cs = department("dept-cs", "CS", "div-fmi", "inst-uvt");
        Group ml = group("g-ml", "ML", List.of("dept-cs"), "inst-uvt", List.of());

        when(orgDivisionRepository.findByHeadUserIdsContaining("ana@uvt.ro")).thenReturn(List.of(fmi));
        when(departmentRepository.findByDivisionIdIn(any())).thenReturn(List.of(cs));
        when(groupRepository.findByDepartmentIdsIn(any())).thenReturn(List.of(ml));
        when(institutionRepository.findAllById(any())).thenReturn(List.of(uvt));
        when(orgDivisionRepository.findAllById(any())).thenReturn(List.of(fmi));
        when(departmentRepository.findAllById(any())).thenReturn(List.of(cs));

        SupervisorWorkspaceService.SupervisorWorkspaceView view = service.buildView("ana@uvt.ro");

        assertEquals("Universitatea de Vest", view.institutionNames().get("inst-uvt"));
        assertEquals("FMI", view.divisionNames().get("div-fmi"));
        assertEquals("CS", view.departmentNames().get("dept-cs"));
    }

    // --- helpers ---

    private static OrgDivision division(String id, String name, String institutionId, DivisionType type) {
        OrgDivision d = new OrgDivision();
        d.setId(id);
        d.setName(name);
        d.setInstitutionId(institutionId);
        d.setType(type);
        return d;
    }

    private static Department department(String id, String name, String divisionId, String institutionId) {
        Department d = new Department();
        d.setId(id);
        d.setName(name);
        d.setDivisionId(divisionId);
        d.setInstitutionId(institutionId);
        return d;
    }

    private static Group group(String id, String name, List<String> deptIds, String institutionId, List<String> supervisors) {
        Group g = new Group();
        g.setId(id);
        g.setName(name);
        g.setDepartmentIds(new java.util.ArrayList<>(deptIds));
        g.setInstitutionId(institutionId);
        g.setSupervisorUserIds(new java.util.ArrayList<>(supervisors));
        return g;
    }
}
