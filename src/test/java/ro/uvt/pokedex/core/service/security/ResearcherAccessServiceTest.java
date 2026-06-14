package ro.uvt.pokedex.core.service.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.org.Membership;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.MembershipRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.SupervisorWorkspaceService;
import ro.uvt.pokedex.core.service.application.SupervisorWorkspaceService.SupervisorWorkspaceView;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearcherAccessServiceTest {

    private static final String SUP = "supervisor@e-uvt.ro";
    private static final String RES = "researcher@e-uvt.ro";

    private final UserService userService = mock(UserService.class);
    private final SupervisorWorkspaceService supervisorWorkspaceService = mock(SupervisorWorkspaceService.class);
    private final DepartmentAffiliationRepository affiliationRepository = mock(DepartmentAffiliationRepository.class);
    private final MembershipRepository membershipRepository = mock(MembershipRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final ResearcherAccessService service = new ResearcherAccessService(
            userService, supervisorWorkspaceService, affiliationRepository, membershipRepository, userRepository);

    private Authentication auth(String principal, String... authorities) {
        return new TestingAuthenticationToken(principal, "x", authorities);
    }

    private Department dept(String id) {
        Department d = new Department();
        d.setId(id);
        return d;
    }

    private Group group(String id) {
        Group g = new Group();
        g.setId(id);
        return g;
    }

    private DepartmentAffiliation affiliation(String userId, String deptId) {
        DepartmentAffiliation a = new DepartmentAffiliation();
        a.setUserId(userId);
        a.setDepartmentId(deptId);
        return a;
    }

    private Membership membership(String userId, String groupId) {
        Membership m = new Membership();
        m.setUserId(userId);
        m.setGroupId(groupId);
        return m;
    }

    private SupervisorWorkspaceView subtree(List<Department> depts, List<Group> groups) {
        return new SupervisorWorkspaceView(List.of(), depts, groups, Map.of(), Map.of(), Map.of());
    }

    // ── admin ────────────────────────────────────────────────────────────────
    @Test
    void adminCanViewAnyResearcher() {
        assertTrue(service.canView(RES, auth("admin@e-uvt.ro", UserRole.PLATFORM_ADMIN.name())));
    }

    @Test
    void adminInScopeReturnsAllResearcherProfiles() {
        User a = new User();
        a.setEmail("a@e-uvt.ro");
        when(userService.findUsersWithResearcherProfile()).thenReturn(List.of(a));
        assertEquals(1, service.findInScopeResearchers(auth("admin@e-uvt.ro", UserRole.PLATFORM_ADMIN.name())).size());
    }

    // ── basic denials ──────────────────────────────────────────────────────────
    @Test
    void researcherRoleCannotView() {
        assertFalse(service.canView(RES, auth(RES, UserRole.RESEARCHER.name())));
    }

    @Test
    void unauthenticatedDenied() {
        assertFalse(service.canView(RES, null));
    }

    @Test
    void canRefreshMirrorsCanView() {
        assertTrue(service.canRefresh(RES, auth("admin@e-uvt.ro", UserRole.PLATFORM_ADMIN.name())));
        assertFalse(service.canRefresh(RES, auth(RES, UserRole.RESEARCHER.name())));
    }

    // ── supervisor scope ────────────────────────────────────────────────────────
    @Test
    void supervisorWithEmptySubtreeDenied() {
        when(supervisorWorkspaceService.buildView(SUP)).thenReturn(SupervisorWorkspaceView.empty());
        assertFalse(service.canView(RES, auth(SUP, UserRole.SUPERVISOR.name())));
    }

    @Test
    void supervisorSeesResearcherViaDepartmentAffiliation() {
        when(supervisorWorkspaceService.buildView(SUP)).thenReturn(subtree(List.of(dept("dept-1")), List.of()));
        when(affiliationRepository.findByUserIdAndValidToIsNull(RES)).thenReturn(List.of(affiliation(RES, "dept-1")));
        assertTrue(service.canView(RES, auth(SUP, UserRole.SUPERVISOR.name())));
    }

    @Test
    void groupOnlySupervisorSeesResearcherViaGroupMembership() {
        // No headed departments — only a supervised group. Group-only supervisors are in scope.
        when(supervisorWorkspaceService.buildView(SUP)).thenReturn(subtree(List.of(), List.of(group("grp-1"))));
        when(affiliationRepository.findByUserIdAndValidToIsNull(RES)).thenReturn(List.of());
        when(membershipRepository.findByUserIdAndValidToIsNull(RES)).thenReturn(List.of(membership(RES, "grp-1")));
        assertTrue(service.canView(RES, auth(SUP, UserRole.SUPERVISOR.name())));
    }

    @Test
    void supervisorDeniedWhenResearcherOutsideSubtree() {
        when(supervisorWorkspaceService.buildView(SUP))
                .thenReturn(subtree(List.of(dept("dept-1")), List.of(group("grp-1"))));
        when(affiliationRepository.findByUserIdAndValidToIsNull(RES)).thenReturn(List.of(affiliation(RES, "dept-OTHER")));
        when(membershipRepository.findByUserIdAndValidToIsNull(RES)).thenReturn(List.of(membership(RES, "grp-OTHER")));
        assertFalse(service.canView(RES, auth(SUP, UserRole.SUPERVISOR.name())));
    }

    @Test
    void supervisorInScopeUnionsAffiliatesAndMembersAndFiltersToResearchers() {
        when(supervisorWorkspaceService.buildView(SUP))
                .thenReturn(subtree(List.of(dept("dept-1")), List.of(group("grp-1"))));
        when(affiliationRepository.findByDepartmentIdInAndValidToIsNull(any()))
                .thenReturn(List.of(affiliation("r1@e-uvt.ro", "dept-1")));
        when(membershipRepository.findByGroupIdInAndValidToIsNull(any()))
                .thenReturn(List.of(membership("r1@e-uvt.ro", "grp-1"), membership("r2@e-uvt.ro", "grp-1")));

        User r1 = new User();
        r1.setEmail("r1@e-uvt.ro");
        r1.setResearcherProfile(new User.ResearcherProfile());
        User r2 = new User();
        r2.setEmail("r2@e-uvt.ro"); // no researcher profile → filtered out
        when(userRepository.findAllById(any())).thenReturn(List.of(r1, r2));

        List<User> scope = service.findInScopeResearchers(auth(SUP, UserRole.SUPERVISOR.name()));
        assertEquals(1, scope.size());
        assertEquals("r1@e-uvt.ro", scope.getFirst().getEmail());
    }
}
