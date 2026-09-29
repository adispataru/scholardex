package ro.uvt.pokedex.core.service.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;

/**
 * Who may approve or reject a researcher's declaration of principal authorship: a head of a department the
 * researcher is affiliated to, a head of the faculty above it, or a platform admin. A supervisor of a group is
 * not enough — the decision belongs to the line that answers for the unit's reports.
 *
 * <p><b>Nobody decides on their own declaration</b>, an admin included: a department director's declarations
 * go to the faculty.</p>
 */
@Service("declarationAccess")
@RequiredArgsConstructor
public class PrincipalAuthorDeclarationAccessService {

    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final OrgUnitAccessService orgUnitAccessService;

    public boolean canDecide(String researcherEmail, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || researcherEmail == null || researcherEmail.isBlank()) {
            return false;
        }
        String approver = authentication.getName();
        if (approver == null || approver.isBlank() || approver.equalsIgnoreCase(researcherEmail.trim())) {
            return false;
        }
        if (isPlatformAdmin(authentication)) {
            return true;
        }
        return departmentAffiliationRepository.findByUserIdAndValidToIsNull(researcherEmail).stream()
                .anyMatch(affiliation -> affiliation.getDepartmentId() != null
                        && orgUnitAccessService.canManageDepartment(affiliation.getDepartmentId(), authentication));
    }

    public boolean isPlatformAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(ga -> UserRole.PLATFORM_ADMIN.name().equals(ga.getAuthority()));
    }
}
