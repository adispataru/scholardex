package ro.uvt.pokedex.core.service.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupAccessServiceTest {

    @Mock private GroupRepository groupRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private OrgDivisionRepository orgDivisionRepository;

    @InjectMocks
    private GroupAccessService service;

    @Test
    void rejectsNullOrUnauthenticated() {
        assertFalse(service.canEdit("g1", null));
        Authentication unauth = new UsernamePasswordAuthenticationToken("u", "p", List.of());
        unauth.setAuthenticated(false);
        assertFalse(service.canEdit("g1", unauth));
    }

    @Test
    void platformAdminBypassesAllOwnershipChecks() {
        Authentication admin = auth("admin@uvt.ro", UserRole.PLATFORM_ADMIN.name());
        // Don't even need the group to exist — admin short-circuits before lookup.
        assertTrue(service.canEdit("any-group", admin));
    }

    @Test
    void unknownGroupDeniesNonAdmin() {
        when(groupRepository.findById("missing")).thenReturn(Optional.empty());
        assertFalse(service.canEdit("missing", auth("sup@uvt.ro", UserRole.SUPERVISOR.name())));
    }

    @Test
    void explicitGroupSupervisorCanEdit() {
        Group group = group("g1", List.of("dept-cs"), List.of("sup@uvt.ro"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));

        assertTrue(service.canEdit("g1", auth("sup@uvt.ro", UserRole.SUPERVISOR.name())));
    }

    @Test
    void departmentHeadCanEditViaDepartmentChain() {
        Group group = group("g1", List.of("dept-cs"), List.of());
        Department cs = department("dept-cs", "div-fmi", List.of("head@uvt.ro"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(departmentRepository.findByIdIn(any())).thenReturn(List.of(cs));
        // Division lookup not strictly needed because the department-head match short-circuits,
        // but stub it lenient in case the implementation evolves.
        lenient().when(orgDivisionRepository.findAllById(any())).thenReturn(List.of());

        assertTrue(service.canEdit("g1", auth("head@uvt.ro", UserRole.SUPERVISOR.name())));
    }

    @Test
    void divisionHeadCanEditViaDivisionChain() {
        Group group = group("g1", List.of("dept-cs"), List.of());
        Department cs = department("dept-cs", "div-fmi", List.of()); // department has no heads
        OrgDivision fmi = division("div-fmi", List.of("dean@uvt.ro"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(departmentRepository.findByIdIn(any())).thenReturn(List.of(cs));
        when(orgDivisionRepository.findAllById(any())).thenReturn(List.of(fmi));

        assertTrue(service.canEdit("g1", auth("dean@uvt.ro", UserRole.SUPERVISOR.name())));
    }

    @Test
    void unrelatedSupervisorIsDeniedEvenIfTheyHaveSupervisorRole() {
        Group group = group("g1", List.of("dept-cs"), List.of("other@uvt.ro"));
        Department cs = department("dept-cs", "div-fmi", List.of("head@uvt.ro"));
        OrgDivision fmi = division("div-fmi", List.of("dean@uvt.ro"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(departmentRepository.findByIdIn(any())).thenReturn(List.of(cs));
        when(orgDivisionRepository.findAllById(any())).thenReturn(List.of(fmi));

        assertFalse(service.canEdit("g1", auth("nobody@uvt.ro", UserRole.SUPERVISOR.name())));
    }

    @Test
    void groupWithNoDepartmentsDeniesNonExplicitSupervisor() {
        Group group = group("g1", List.of(), List.of("other@uvt.ro"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));

        assertFalse(service.canEdit("g1", auth("nobody@uvt.ro", UserRole.SUPERVISOR.name())));
    }

    @Test
    void canViewMirrorsCanEdit() {
        Group group = group("g1", List.of("dept-cs"), List.of("sup@uvt.ro"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));

        Authentication sup = auth("sup@uvt.ro", UserRole.SUPERVISOR.name());
        assertTrue(service.canView("g1", sup));
        assertTrue(service.canEdit("g1", sup));
    }

    // --- helpers ---

    private static Authentication auth(String email, String authority) {
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority(authority));
        return new UsernamePasswordAuthenticationToken(email, "n/a", auths);
    }

    private static Group group(String id, List<String> deptIds, List<String> supervisorIds) {
        Group g = new Group();
        g.setId(id);
        g.setDepartmentIds(new ArrayList<>(deptIds));
        g.setSupervisorUserIds(new ArrayList<>(supervisorIds));
        return g;
    }

    private static Department department(String id, String divisionId, List<String> headUserIds) {
        Department d = new Department();
        d.setId(id);
        d.setDivisionId(divisionId);
        d.setHeadUserIds(new ArrayList<>(headUserIds));
        return d;
    }

    private static OrgDivision division(String id, List<String> headUserIds) {
        OrgDivision d = new OrgDivision();
        d.setId(id);
        d.setHeadUserIds(new ArrayList<>(headUserIds));
        return d;
    }
}
