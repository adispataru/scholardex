package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Department ⇄ user affiliation operations for the supervisor roster editor — the department-level
 * twin of {@link GroupMembershipService}. Affiliations are temporal: adding creates a current record
 * ({@code validFrom=today}, {@code validTo=null}); removing soft-closes it ({@code validTo=today}) so
 * history — and the roll-up deltas that read it — is preserved. Only "current" affiliations
 * ({@code validTo == null}) are exposed by the standard lookups.
 *
 * <p>Scope: mutating an existing user's department membership only. Account creation, head
 * appointment, and staff CSV import stay with the platform admin.</p>
 */
@Service
@RequiredArgsConstructor
public class DepartmentAffiliationService {

    private final DepartmentAffiliationRepository departmentAffiliationRepository;

    public List<DepartmentAffiliation> listCurrentAffiliations(String departmentId) {
        if (departmentId == null || departmentId.isBlank()) return List.of();
        return departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull(departmentId);
    }

    /**
     * Adds {@code userId} to {@code departmentId} as a current affiliation. Idempotent — a no-op if the
     * user is already currently affiliated. The new affiliation is {@code primary} only when the user has
     * no other current department (so a joint appointment doesn't silently steal the primary flag, matching
     * the staff-import invariant of one primary per person).
     *
     * @return true if a new affiliation was created
     */
    public boolean addMember(String departmentId, String userId) {
        if (departmentId == null || departmentId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        Optional<DepartmentAffiliation> existing = departmentAffiliationRepository
                .findByDepartmentIdAndUserIdAndValidToIsNull(departmentId, userId);
        if (existing.isPresent()) {
            return false;
        }
        boolean hasOtherCurrentDept = !departmentAffiliationRepository
                .findByUserIdAndValidToIsNull(userId).isEmpty();
        DepartmentAffiliation affiliation = new DepartmentAffiliation();
        affiliation.setUserId(userId);
        affiliation.setDepartmentId(departmentId);
        affiliation.setPrimary(!hasOtherCurrentDept);
        affiliation.setValidFrom(LocalDate.now());
        affiliation.setCreatedAt(Instant.now());
        departmentAffiliationRepository.save(affiliation);
        return true;
    }

    /**
     * Soft-closes {@code userId}'s current affiliation with {@code departmentId} ({@code validTo=today}).
     *
     * @return true if a current affiliation was found and closed
     */
    public boolean removeMember(String departmentId, String userId) {
        Optional<DepartmentAffiliation> current = departmentAffiliationRepository
                .findByDepartmentIdAndUserIdAndValidToIsNull(departmentId, userId);
        if (current.isEmpty()) {
            return false;
        }
        DepartmentAffiliation affiliation = current.get();
        affiliation.setValidTo(LocalDate.now());
        departmentAffiliationRepository.save(affiliation);
        return true;
    }
}
