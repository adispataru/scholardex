package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.User;
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

        facade.createGroup(group, List.of("a@b.com"));

        assertEquals("uvt", group.getInstitutionId());
        assertNotNull(group.getCreatedAt());
        verify(groupRepository).save(group);
        verify(groupMembershipService).syncMembers("g1", List.of("a@b.com"));
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
    void deleteGroupDeletesById() {
        facade.deleteGroup("g1");

        verify(groupRepository, times(1)).deleteById("g1");
    }
}
