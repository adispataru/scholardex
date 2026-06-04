package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DivisionType;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.UserService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupManagementFacadeTest {

    @Mock private GroupRepository groupRepository;
    @Mock private DomainRepository domainRepository;
    @Mock private InstitutionRepository institutionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private GroupMembershipService groupMembershipService;

    @InjectMocks
    private GroupManagementFacade facade;

    @Test
    void buildGroupListViewReturnsAllRequiredData() {
        Group group = new Group();
        Domain domain = new Domain();
        Institution institution = new Institution();
        User user = new User();

        when(groupRepository.findAll()).thenReturn(List.of(group));
        when(domainRepository.findAll()).thenReturn(List.of(domain));
        when(institutionRepository.findAll()).thenReturn(List.of(institution));
        when(userService.findUsersWithResearcherProfile()).thenReturn(List.of(user));

        var vm = facade.buildGroupListView();

        assertEquals(1, vm.groups().size());
        assertEquals(1, vm.allDomains().size());
        assertEquals(1, vm.institutions().size());
        assertEquals(1, vm.allResearchers().size());
        assertNotNull(vm.group());
    }

    @Test
    void buildGroupListViewReturnsSortedDepartmentOptionsWithLabels() {
        Department cs = department("dept-cs", "Computer Science", "div-fmi", "uvt");
        Department math = department("dept-math", "Mathematics", "div-fmi", "uvt");
        Department infra = department("dept-infra", "Research Infrastructure", "div-service", "uvt");
        OrgDivision faculty = division("div-fmi", "Faculty of Mathematics and Computer Science", DivisionType.FACULTY);
        OrgDivision service = division("div-service", "Centrul de Calcul Service", DivisionType.SERVICE);
        Institution institution = new Institution();
        institution.setId("uvt");
        institution.setName("UVT");

        when(groupRepository.findAll()).thenReturn(List.of());
        when(domainRepository.findAll()).thenReturn(List.of());
        when(institutionRepository.findAll()).thenReturn(List.of(institution));
        when(departmentRepository.findAll()).thenReturn(List.of(cs, infra, math));
        when(orgDivisionRepository.findAll()).thenReturn(List.of(faculty, service));
        when(userService.findUsersWithResearcherProfile()).thenReturn(List.of());

        var vm = facade.buildGroupListView();

        assertEquals(List.of("dept-infra", "dept-cs", "dept-math"),
                vm.departmentOptions().stream().map(option -> option.id()).toList());
        assertEquals("UVT · Centrul de Calcul Service · Research Infrastructure",
                vm.departmentOptions().get(0).label());
        assertEquals("UVT · Faculty of Mathematics and Computer Science · Computer Science",
                vm.departmentOptions().get(1).label());
    }

    @Test
    void buildGroupEditViewMapsGroupAndReferenceData() {
        Group group = new Group();
        group.setId("g1");
        group.setName("G1");
        Domain domain = new Domain();
        Institution institution = new Institution();
        User user = new User();

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(domainRepository.findAll()).thenReturn(List.of(domain));
        when(institutionRepository.findAll()).thenReturn(List.of(institution));
        when(userService.findUsersWithResearcherProfile()).thenReturn(List.of(user));
        when(groupMembershipService.listCurrentMemberUserIds("g1")).thenReturn(List.of("a@b.com"));

        var vm = facade.buildGroupEditView("g1");

        assertNotNull(vm.group());
        assertEquals("G1", vm.group().getName());
        assertEquals(1, vm.domains().size());
        assertEquals(1, vm.institutions().size());
        assertEquals(1, vm.allResearchers().size());
        assertEquals(List.of("a@b.com"), vm.currentMemberUserIds());
    }

    @Test
    void createGroupRebuildsInstitutionIdFromDepartmentsAndSyncsMembers() {
        Group group = new Group();
        group.setDepartmentIds(new java.util.ArrayList<>(List.of("d1")));
        Department department = new Department();
        department.setId("d1");
        department.setInstitutionId("uvt");
        when(departmentRepository.findByIdIn(List.of("d1"))).thenReturn(List.of(department));
        when(groupRepository.save(any(Group.class))).thenAnswer(inv -> {
            Group g = inv.getArgument(0);
            if (g.getId() == null) g.setId("g1");
            return g;
        });

        Group result = facade.createGroup(group, List.of("a@b.com"));

        assertSame(group, result);
        assertEquals("uvt", group.getInstitutionId());
        assertNotNull(group.getCreatedAt());
        assertNotNull(group.getUpdatedAt());
        verify(groupRepository).save(group);
        verify(groupMembershipService).syncMembers("g1", List.of("a@b.com"));
    }

    @Test
    void createGroupClearsInstitutionIdWhenNoDepartmentsRemainAfterCleaning() {
        Group group = new Group();
        group.setInstitutionId("client-value");
        group.setDepartmentIds(new java.util.ArrayList<>(List.of("undefined", "null")));
        when(groupRepository.save(group)).thenReturn(group);

        Group result = facade.createGroup(group, List.of());

        assertSame(group, result);
        assertEquals(new java.util.ArrayList<>(), group.getDepartmentIds());
        assertNull(group.getInstitutionId());
        verify(departmentRepository, never()).findByIdIn(any());
    }

    @Test
    void createGroupDropsMalformedPlaceholderIdsBeforeSaving() {
        Group group = new Group();
        group.setDomainIds(new java.util.ArrayList<>(List.of("undefined", "CS", "null", "CS")));
        group.setDepartmentIds(new java.util.ArrayList<>(List.of("undefined", "d1")));
        group.setSupervisorUserIds(new java.util.ArrayList<>(List.of("undefined", "boss@example.test")));
        Department department = new Department();
        department.setId("d1");
        department.setInstitutionId("uvt");
        User supervisor = new User();
        supervisor.setEmail("boss@example.test");

        when(departmentRepository.findByIdIn(List.of("d1"))).thenReturn(List.of(department));
        when(userRepository.findAllById(List.of("boss@example.test"))).thenReturn(List.of(supervisor));
        when(groupRepository.save(any(Group.class))).thenAnswer(inv -> inv.getArgument(0));

        facade.createGroup(group, List.of());

        assertEquals(List.of("CS"), group.getDomainIds());
        assertEquals(List.of("d1"), group.getDepartmentIds());
        assertEquals(List.of("boss@example.test"), group.getSupervisorUserIds());
        verify(groupRepository).save(group);
    }

    @Test
    void updateGroupDropsMalformedIdsBeforeSavingAndReturnsSavedGroup() {
        Group group = new Group();
        group.setId("g1");
        group.setDomainIds(new java.util.ArrayList<>(List.of("undefined", "CS", "null", "CS")));
        group.setDepartmentIds(new java.util.ArrayList<>(List.of("undefined", "d1")));
        group.setSupervisorUserIds(new java.util.ArrayList<>(List.of("undefined", "boss@example.test")));
        Department department = new Department();
        department.setId("d1");
        department.setInstitutionId("uvt");
        User supervisor = new User();
        supervisor.setEmail("boss@example.test");
        Group saved = new Group();
        saved.setId("saved-g1");

        when(departmentRepository.findByIdIn(List.of("d1"))).thenReturn(List.of(department));
        when(userRepository.findAllById(List.of("boss@example.test"))).thenReturn(List.of(supervisor));
        when(groupRepository.save(group)).thenReturn(saved);

        Group result = facade.updateGroup(group, List.of("member@example.test"));

        assertSame(saved, result);
        assertEquals(List.of("CS"), group.getDomainIds());
        assertEquals(List.of("d1"), group.getDepartmentIds());
        assertEquals(List.of("boss@example.test"), group.getSupervisorUserIds());
        assertEquals("uvt", group.getInstitutionId());
        assertNotNull(group.getUpdatedAt());
        verify(groupMembershipService).syncMembers("saved-g1", List.of("member@example.test"));
    }

    @Test
    void updateGroupRejectsUnknownSupervisorsAfterCleaningPlaceholders() {
        Group group = new Group();
        group.setId("g1");
        group.setSupervisorUserIds(new java.util.ArrayList<>(List.of("undefined", "missing@example.test")));
        when(userRepository.findAllById(List.of("missing@example.test"))).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> facade.updateGroup(group, List.of()));

        assertTrue(ex.getMessage().contains("missing@example.test"));
        verify(groupRepository, never()).save(any(Group.class));
        verify(groupMembershipService, never()).syncMembers(anyString(), any());
    }

    @Test
    void updateGroupRejectsDepartmentsAcrossMultipleInstitutions() {
        Group group = new Group();
        group.setId("g1");
        group.setDepartmentIds(new java.util.ArrayList<>(List.of("d1", "d2")));
        Department d1 = new Department();
        d1.setId("d1");
        d1.setInstitutionId("uvt");
        Department d2 = new Department();
        d2.setId("d2");
        d2.setInstitutionId("other");
        when(departmentRepository.findByIdIn(List.of("d1", "d2"))).thenReturn(List.of(d1, d2));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> facade.updateGroup(group, List.of()));

        assertTrue(ex.getMessage().contains("multiple institutions"));
        verify(groupRepository, never()).save(any(Group.class));
        verify(groupMembershipService, never()).syncMembers(anyString(), any());
    }

    @Test
    void updateGroupRejectsUnknownDepartmentIds() {
        Group group = new Group();
        group.setId("g1");
        group.setDepartmentIds(new java.util.ArrayList<>(List.of("missing")));
        when(departmentRepository.findByIdIn(List.of("missing"))).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> facade.updateGroup(group, List.of()));

        assertTrue(ex.getMessage().contains("Departments not found"));
        verify(groupRepository, never()).save(any(Group.class));
        verify(groupMembershipService, never()).syncMembers(anyString(), any());
    }

    @Test
    void buildGroupListViewHandlesDepartmentsWithoutDivisionOrInstitutionNames() {
        Department orphan = department("dept-orphan", "Standalone", null, null);
        Department unknownInstitution = department("dept-unknown", "Unknown Institution", "div-x", "missing-inst");
        OrgDivision divisionWithoutType = division("div-x", "Uncatalogued Division", null);

        when(groupRepository.findAll()).thenReturn(List.of());
        when(domainRepository.findAll()).thenReturn(List.of());
        when(institutionRepository.findAll()).thenReturn(List.of());
        when(departmentRepository.findAll()).thenReturn(List.of(unknownInstitution, orphan));
        when(orgDivisionRepository.findAll()).thenReturn(List.of(divisionWithoutType));
        when(userService.findUsersWithResearcherProfile()).thenReturn(List.of());

        var options = facade.buildGroupListView().departmentOptions();

        assertEquals(List.of("dept-orphan", "dept-unknown"),
                options.stream().map(option -> option.id()).toList());
        assertEquals("Standalone", options.get(0).label());
        assertEquals("Uncatalogued Division · Unknown Institution", options.get(1).label());
        assertNull(options.get(1).divisionType());
        assertNull(options.get(1).institutionShortName());
    }

    @Test
    void updateGroupAlwaysSyncsMembershipsEvenWhenListIsNull() {
        Group group = new Group();
        group.setId("g1");
        when(groupRepository.save(group)).thenReturn(group);

        facade.updateGroup(group, null);

        verify(groupMembershipService).syncMembers("g1", List.of());
    }

    @Test
    void idListCleanerReturnsMutableEmptyListForNullAndEmptyInputs() {
        List<String> fromNull = IdListCleaner.clean(null);
        List<String> fromEmpty = IdListCleaner.clean(List.of());

        fromNull.add("next");
        fromEmpty.add("next");

        assertEquals(List.of("next"), fromNull);
        assertEquals(List.of("next"), fromEmpty);
    }

    @Test
    void deleteGroupDeletesById() {
        facade.deleteGroup("g1");

        verify(groupRepository, times(1)).deleteById("g1");
    }

    private static Department department(String id, String name, String divisionId, String institutionId) {
        Department department = new Department();
        department.setId(id);
        department.setName(name);
        department.setDivisionId(divisionId);
        department.setInstitutionId(institutionId);
        return department;
    }

    private static OrgDivision division(String id, String name, DivisionType type) {
        OrgDivision division = new OrgDivision();
        division.setId(id);
        division.setName(name);
        division.setType(type);
        return division;
    }
}
