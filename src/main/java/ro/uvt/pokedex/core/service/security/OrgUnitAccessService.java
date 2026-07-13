package ro.uvt.pokedex.core.service.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;

import java.util.Optional;

/**
 * Resolves whether the current principal may manage the roster of a department. A head of the
 * department ({@code Department.headUserIds}) qualifies, as does the head of the division above it
 * ({@code OrgDivision.headUserIds}) — the same implicit-supervision cascade {@link GroupAccessService}
 * uses for groups. PLATFORM_ADMIN bypasses every check.
 *
 * <p>Wired through SpEL via {@code @PreAuthorize("@orgUnitAccess.canManageDepartment(#id, authentication)")}.
 * Managing roster = adding/removing existing researchers only; creating units, appointing heads, and
 * account provisioning remain platform-admin operations under {@code /admin/**}.</p>
 */
@Service("orgUnitAccess")
@RequiredArgsConstructor
public class OrgUnitAccessService {

    private final DepartmentRepository departmentRepository;
    private final OrgDivisionRepository orgDivisionRepository;

    public boolean canManageDepartment(String departmentId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasPlatformAdmin(authentication)) {
            return true;
        }
        String userId = authentication.getName();
        if (userId == null || userId.isBlank() || departmentId == null || departmentId.isBlank()) {
            return false;
        }
        Optional<Department> deptOpt = departmentRepository.findById(departmentId);
        if (deptOpt.isEmpty()) {
            return false;
        }
        Department department = deptOpt.get();
        if (department.getHeadUserIds() != null && department.getHeadUserIds().contains(userId)) {
            return true;
        }
        if (department.getDivisionId() == null) {
            return false;
        }
        return orgDivisionRepository.findById(department.getDivisionId())
                .map(OrgDivision::getHeadUserIds)
                .map(heads -> heads != null && heads.contains(userId))
                .orElse(false);
    }

    private boolean hasPlatformAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(ga -> UserRole.PLATFORM_ADMIN.name().equals(ga.getAuthority()));
    }
}
