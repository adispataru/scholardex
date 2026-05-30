package ro.uvt.pokedex.core.service.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves whether the current principal may view or edit a group. Implicit
 * supervision walks Group ⇒ Department ⇒ OrgDivision; explicit supervision sits
 * on {@code Group.supervisorUserIds}. PLATFORM_ADMIN bypasses every check.
 *
 * <p>Wired through SpEL via {@code @PreAuthorize("@groupAccess.canEdit(#id, authentication)")}.
 */
@Service("groupAccess")
@RequiredArgsConstructor
public class GroupAccessService {

    private final GroupRepository groupRepository;
    private final DepartmentRepository departmentRepository;
    private final OrgDivisionRepository orgDivisionRepository;

    public boolean canView(String groupId, Authentication authentication) {
        return canEdit(groupId, authentication);
    }

    public boolean canEdit(String groupId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasPlatformAdmin(authentication)) {
            return true;
        }
        String userId = authentication.getName();
        if (userId == null || userId.isBlank()) {
            return false;
        }
        Optional<Group> groupOpt = groupRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return false;
        }
        return isSupervisor(groupOpt.get(), userId);
    }

    public boolean isSupervisorOf(Group group, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || group == null) {
            return false;
        }
        if (hasPlatformAdmin(authentication)) {
            return true;
        }
        String userId = authentication.getName();
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return isSupervisor(group, userId);
    }

    private boolean isSupervisor(Group group, String userId) {
        if (group.getSupervisorUserIds() != null && group.getSupervisorUserIds().contains(userId)) {
            return true;
        }
        List<String> departmentIds = group.getDepartmentIds();
        if (departmentIds == null || departmentIds.isEmpty()) {
            return false;
        }
        List<Department> departments = departmentRepository.findByIdIn(departmentIds);
        Set<String> divisionIds = new HashSet<>();
        for (Department department : departments) {
            if (department.getHeadUserIds() != null && department.getHeadUserIds().contains(userId)) {
                return true;
            }
            if (department.getDivisionId() != null) {
                divisionIds.add(department.getDivisionId());
            }
        }
        if (divisionIds.isEmpty()) {
            return false;
        }
        for (OrgDivision division : orgDivisionRepository.findAllById(divisionIds)) {
            if (division.getHeadUserIds() != null && division.getHeadUserIds().contains(userId)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPlatformAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(ga -> UserRole.PLATFORM_ADMIN.name().equals(ga.getAuthority()));
    }
}
