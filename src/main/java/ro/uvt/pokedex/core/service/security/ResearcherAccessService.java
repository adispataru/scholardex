package ro.uvt.pokedex.core.service.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.MembershipRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.SupervisorWorkspaceService;
import ro.uvt.pokedex.core.service.application.SupervisorWorkspaceService.SupervisorWorkspaceView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether the current principal (admin or supervisor) may view/refresh a specific
 * researcher's individual evaluation report, and enumerates the researchers in scope.
 *
 * <p>Wired through SpEL via {@code @PreAuthorize("@researcherAccess.canView(#email, authentication)")}.
 *
 * <p>Scope model (H59): a PLATFORM_ADMIN sees every researcher. A SUPERVISOR sees a researcher who
 * is reachable from the supervisor's supervised subtree — as resolved by
 * {@link SupervisorWorkspaceService} (divisions/departments they head, plus groups they supervise
 * explicitly or via a headed department/division) — through <em>either</em> a current
 * {@code DepartmentAffiliation} in a subtree department <em>or</em> a current {@code Membership} in
 * a subtree group. "Current" means {@code validTo == null}. Group-only supervisors are included.
 *
 * <p>{@link #canRefresh} is kept separate from {@link #canView} so refresh authorization can
 * diverge (H59 slice 3) without touching call sites.
 */
@Service("researcherAccess")
@RequiredArgsConstructor
public class ResearcherAccessService {

    private final UserService userService;
    private final SupervisorWorkspaceService supervisorWorkspaceService;
    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;

    public boolean canView(String researcherEmail, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasAuthority(authentication, UserRole.PLATFORM_ADMIN)) {
            return true;
        }
        if (!hasAuthority(authentication, UserRole.SUPERVISOR)) {
            return false;
        }
        if (researcherEmail == null || researcherEmail.isBlank()) {
            return false;
        }
        String supervisorId = authentication.getName();
        if (supervisorId == null || supervisorId.isBlank()) {
            return false;
        }

        SupervisorWorkspaceView subtree = supervisorWorkspaceService.buildView(supervisorId);
        if (subtree.isEmpty()) {
            return false;
        }
        Set<String> departmentIds = subtree.departments().stream()
                .map(Department::getId).collect(Collectors.toSet());
        Set<String> groupIds = subtree.groups().stream()
                .map(Group::getId).collect(Collectors.toSet());

        boolean byAffiliation = !departmentIds.isEmpty()
                && departmentAffiliationRepository.findByUserIdAndValidToIsNull(researcherEmail).stream()
                .anyMatch(a -> departmentIds.contains(a.getDepartmentId()));
        if (byAffiliation) {
            return true;
        }
        return !groupIds.isEmpty()
                && membershipRepository.findByUserIdAndValidToIsNull(researcherEmail).stream()
                .anyMatch(m -> groupIds.contains(m.getGroupId()));
    }

    public boolean canRefresh(String researcherEmail, Authentication authentication) {
        return canView(researcherEmail, authentication);
    }

    public List<User> findInScopeResearchers(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return List.of();
        }
        if (hasAuthority(authentication, UserRole.PLATFORM_ADMIN)) {
            return userService.findUsersWithResearcherProfile();
        }
        if (!hasAuthority(authentication, UserRole.SUPERVISOR)) {
            return List.of();
        }
        String supervisorId = authentication.getName();
        if (supervisorId == null || supervisorId.isBlank()) {
            return List.of();
        }

        SupervisorWorkspaceView subtree = supervisorWorkspaceService.buildView(supervisorId);
        if (subtree.isEmpty()) {
            return List.of();
        }
        Set<String> departmentIds = subtree.departments().stream()
                .map(Department::getId).collect(Collectors.toSet());
        Set<String> groupIds = subtree.groups().stream()
                .map(Group::getId).collect(Collectors.toSet());

        // Union of current affiliates of subtree departments and current members of subtree groups.
        Set<String> emails = new LinkedHashSet<>();
        if (!departmentIds.isEmpty()) {
            departmentAffiliationRepository.findByDepartmentIdInAndValidToIsNull(departmentIds)
                    .forEach(a -> {
                        if (a.getUserId() != null) emails.add(a.getUserId());
                    });
        }
        if (!groupIds.isEmpty()) {
            membershipRepository.findByGroupIdInAndValidToIsNull(groupIds)
                    .forEach(m -> {
                        if (m.getUserId() != null) emails.add(m.getUserId());
                    });
        }
        if (emails.isEmpty()) {
            return List.of();
        }

        // Only researchers (those with a profile) have an individual report to view.
        List<User> researchers = new ArrayList<>();
        userRepository.findAllById(emails).forEach(u -> {
            if (u.getResearcherProfile() != null) researchers.add(u);
        });
        researchers.sort(Comparator.comparing(u -> u.getEmail() == null ? "" : u.getEmail()));
        return researchers;
    }

    private boolean hasAuthority(Authentication authentication, UserRole role) {
        return authentication.getAuthorities().stream()
                .anyMatch(ga -> role.name().equals(ga.getAuthority()));
    }
}
