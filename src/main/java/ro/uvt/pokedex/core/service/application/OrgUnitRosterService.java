package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the researcher roster of an org unit. Extracted from the report facades so every
 * org-unit report surface resolves members identically: current (validTo == null) affiliations,
 * users without a researcher profile dropped, sorted by display name.
 *
 * <p>H105: EXTERNAL accounts (candidates with an applicant affiliation) are dropped by default
 * ({@link RosterScope#STAFF}) so they never count in a unit's numbers; the batch refresh asks for
 * {@link RosterScope#ALL} so a candidate's run still stays current for the head who evaluates them.</p>
 */
@Service
@RequiredArgsConstructor
public class OrgUnitRosterService {

    private final DepartmentRepository departmentRepository;
    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final UserRepository userRepository;
    private final GroupMembershipService groupMembershipService;

    /**
     * A unit member. {@code departmentLabel} is empty except for division rosters, where it names
     * the contributing department(s) — "A + B" for joint appointments.
     */
    public record RosterMember(User user, String departmentLabel) {}

    /** Which accounts a roster includes: staff only (the default for every number) or everyone affiliated. */
    public enum RosterScope { STAFF, ALL }

    /**
     * Researchers across every department directly under the division. Joint-appointed
     * researchers are de-duplicated; the label records each department that contributed them.
     */
    public List<RosterMember> divisionRoster(String divisionId) {
        return divisionRoster(divisionId, RosterScope.STAFF);
    }

    public List<RosterMember> divisionRoster(String divisionId, RosterScope scope) {
        List<Department> departments = departmentRepository.findByDivisionId(divisionId);
        Map<String, String> labelByUserId = new LinkedHashMap<>();
        Set<String> userIds = new LinkedHashSet<>();
        for (Department d : departments) {
            for (DepartmentAffiliation a : departmentAffiliationRepository
                    .findByDepartmentIdAndValidToIsNull(d.getId())) {
                if (userIds.add(a.getUserId())) {
                    labelByUserId.put(a.getUserId(), d.getName() == null ? d.getId() : d.getName());
                } else {
                    // Joint appointment — append the secondary department.
                    String existing = labelByUserId.get(a.getUserId());
                    String addName = d.getName() == null ? d.getId() : d.getName();
                    if (existing != null && !existing.contains(addName)) {
                        labelByUserId.put(a.getUserId(), existing + " + " + addName);
                    }
                }
            }
        }
        return toMembers(userIds, labelByUserId, scope);
    }

    /** Researchers currently affiliated with the department. */
    public List<RosterMember> departmentRoster(String departmentId) {
        return departmentRoster(departmentId, RosterScope.STAFF);
    }

    public List<RosterMember> departmentRoster(String departmentId, RosterScope scope) {
        List<DepartmentAffiliation> affiliations =
                departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull(departmentId);
        LinkedHashSet<String> userIds = new LinkedHashSet<>();
        for (DepartmentAffiliation a : affiliations) userIds.add(a.getUserId());
        return toMembers(userIds, Map.of(), scope);
    }

    /** Researchers currently in the group (current memberships, i.e. validTo == null). */
    public List<RosterMember> groupRoster(String groupId) {
        return groupRoster(groupId, RosterScope.STAFF);
    }

    public List<RosterMember> groupRoster(String groupId, RosterScope scope) {
        return toMembers(new LinkedHashSet<>(groupMembershipService.listCurrentMemberUserIds(groupId)), Map.of(), scope);
    }

    private List<RosterMember> toMembers(Set<String> userIds, Map<String, String> labelByUserId, RosterScope scope) {
        if (userIds.isEmpty()) return List.of();
        List<User> researchers = new ArrayList<>(userRepository.findAllById(userIds));
        researchers.removeIf(u -> u.getResearcherProfile() == null);
        if (scope == RosterScope.STAFF) researchers.removeIf(User::isExternal);
        researchers.sort(Comparator.comparing(u -> u.getResearcherProfile().getName()));
        return researchers.stream()
                .map(u -> new RosterMember(u, labelByUserId.getOrDefault(u.getEmail(), "")))
                .toList();
    }
}
