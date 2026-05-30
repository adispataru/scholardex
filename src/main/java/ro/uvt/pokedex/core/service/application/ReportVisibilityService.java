package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentReportHide;
import ro.uvt.pokedex.core.model.org.DivisionReportSelection;
import ro.uvt.pokedex.core.model.org.Membership;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.org.DepartmentReportHideRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.DivisionReportSelectionRepository;
import ro.uvt.pokedex.core.repository.org.MembershipRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Manages report visibility across the org hierarchy.
 *
 * <ul>
 *   <li><strong>Selection</strong> ({@link DivisionReportSelection}): a positive choice by a
 *       division head — "this report applies to my division". Without a selection, no users
 *       under the division see the report.</li>
 *   <li><strong>Hide</strong> ({@link DepartmentReportHide}): a subtractive override by a
 *       department head — "the parent division selected this, but my department shouldn't see
 *       it". Department heads cannot add reports; only hide.</li>
 * </ul>
 *
 * <p>A report is <em>visible to a user</em> through a group {@code G} when:
 * {@code G}'s division has it selected AND none of {@code G}'s departments have hidden it.
 * The union across all the user's groups gives their final reports list.
 */
@Service
@RequiredArgsConstructor
public class ReportVisibilityService {

    private final DivisionReportSelectionRepository divisionReportSelectionRepository;
    private final DepartmentReportHideRepository departmentReportHideRepository;
    private final IndividualReportRepository individualReportRepository;
    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentRepository departmentRepository;
    private final GroupRepository groupRepository;
    private final MembershipRepository membershipRepository;

    // -------- Listings used by admin pages --------

    /** All catalog reports + which ones are currently selected for the given division. */
    public DivisionSelectionView buildDivisionSelectionView(String divisionId) {
        Set<String> selected = new HashSet<>();
        for (DivisionReportSelection s : divisionReportSelectionRepository.findByDivisionId(divisionId)) {
            selected.add(s.getReportId());
        }
        return new DivisionSelectionView(individualReportRepository.findAll(), selected);
    }

    /**
     * Inherited reports for a department (from the parent division's selection), each with
     * a flag indicating whether this department has hidden it.
     */
    public DepartmentVisibilityView buildDepartmentVisibilityView(String departmentId) {
        Optional<Department> deptOpt = departmentRepository.findById(departmentId);
        if (deptOpt.isEmpty()) return new DepartmentVisibilityView(List.of(), Set.of());

        List<IndividualReport> inherited = inheritedReportsForDepartment(deptOpt.get());
        Set<String> hidden = new HashSet<>();
        for (DepartmentReportHide h : departmentReportHideRepository.findByDepartmentId(departmentId)) {
            hidden.add(h.getReportId());
        }
        return new DepartmentVisibilityView(inherited, hidden);
    }

    /** Reports actually exposed to users in the given department (selected at division, not hidden here). */
    public List<IndividualReport> listVisibleReportsForDepartment(String departmentId) {
        Optional<Department> deptOpt = departmentRepository.findById(departmentId);
        if (deptOpt.isEmpty()) return List.of();
        List<IndividualReport> inherited = inheritedReportsForDepartment(deptOpt.get());
        if (inherited.isEmpty()) return List.of();
        Set<String> hidden = new HashSet<>();
        for (DepartmentReportHide h : departmentReportHideRepository.findByDepartmentId(departmentId)) {
            hidden.add(h.getReportId());
        }
        if (hidden.isEmpty()) return inherited;
        List<IndividualReport> visible = new ArrayList<>();
        for (IndividualReport r : inherited) if (!hidden.contains(r.getId())) visible.add(r);
        return visible;
    }

    /** Reports selected for this division (no per-department hide applied — that's a department concern). */
    public List<IndividualReport> listVisibleReportsForDivision(String divisionId) {
        List<DivisionReportSelection> selections = divisionReportSelectionRepository.findByDivisionId(divisionId);
        if (selections.isEmpty()) return List.of();
        Set<String> ids = new HashSet<>();
        for (DivisionReportSelection s : selections) ids.add(s.getReportId());
        return individualReportRepository.findAllById(ids);
    }

    /** Union of reports visible to the user via any of their current groups. */
    public List<IndividualReport> listVisibleReportsForUser(String userId) {
        if (userId == null || userId.isBlank()) return List.of();

        List<Membership> memberships = membershipRepository.findByUserIdAndValidToIsNull(userId);
        if (memberships.isEmpty()) return List.of();

        Set<String> groupIds = new LinkedHashSet<>();
        for (Membership m : memberships) groupIds.add(m.getGroupId());
        List<Group> groups = new ArrayList<>();
        groupRepository.findAllById(groupIds).forEach(groups::add);

        Set<String> visibleReportIds = new LinkedHashSet<>();
        for (Group g : groups) {
            List<String> deptIds = g.getDepartmentIds();
            if (deptIds == null || deptIds.isEmpty()) continue;

            // Resolve divisions for these departments.
            List<Department> depts = departmentRepository.findByIdIn(deptIds);
            Set<String> divisionIds = new HashSet<>();
            for (Department d : depts) if (d.getDivisionId() != null) divisionIds.add(d.getDivisionId());
            if (divisionIds.isEmpty()) continue;

            // Selections at any of those divisions.
            Set<String> selectedHere = new HashSet<>();
            for (DivisionReportSelection s : divisionReportSelectionRepository.findByDivisionIdIn(divisionIds)) {
                selectedHere.add(s.getReportId());
            }
            if (selectedHere.isEmpty()) continue;

            // Hides at any of the group's departments.
            Set<String> hiddenHere = new HashSet<>();
            for (DepartmentReportHide h : departmentReportHideRepository.findByDepartmentIdIn(deptIds)) {
                hiddenHere.add(h.getReportId());
            }

            for (String rid : selectedHere) if (!hiddenHere.contains(rid)) visibleReportIds.add(rid);
        }

        if (visibleReportIds.isEmpty()) return List.of();
        return individualReportRepository.findAllById(visibleReportIds);
    }

    // -------- Mutations (with access checks) --------

    /** Toggle a division-level selection. */
    public void setDivisionSelection(String divisionId, String reportId,
                                     boolean selected, Authentication auth) {
        OrgDivision division = orgDivisionRepository.findById(divisionId)
                .orElseThrow(() -> new IllegalArgumentException("Division not found: " + divisionId));
        requireDivisionManage(division, auth);

        if (selected) {
            if (divisionReportSelectionRepository.findByDivisionIdAndReportId(divisionId, reportId).isEmpty()) {
                DivisionReportSelection s = new DivisionReportSelection();
                s.setDivisionId(divisionId);
                s.setReportId(reportId);
                s.setSelectedBy(auth.getName());
                s.setSelectedAt(Instant.now());
                divisionReportSelectionRepository.save(s);
            }
        } else {
            divisionReportSelectionRepository.deleteByDivisionIdAndReportId(divisionId, reportId);
        }
    }

    /** Toggle a department-level hide. */
    public void setDepartmentHide(String departmentId, String reportId,
                                  boolean hidden, Authentication auth) {
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new IllegalArgumentException("Department not found: " + departmentId));
        requireDepartmentManage(department, auth);

        if (hidden) {
            if (departmentReportHideRepository.findByDepartmentIdAndReportId(departmentId, reportId).isEmpty()) {
                DepartmentReportHide h = new DepartmentReportHide();
                h.setDepartmentId(departmentId);
                h.setReportId(reportId);
                h.setHiddenBy(auth.getName());
                h.setHiddenAt(Instant.now());
                departmentReportHideRepository.save(h);
            }
        } else {
            departmentReportHideRepository.deleteByDepartmentIdAndReportId(departmentId, reportId);
        }
    }

    /** PLATFORM_ADMIN or a head of this division. */
    public boolean canManageDivisionSelection(OrgDivision division, Authentication auth) {
        if (auth == null) return false;
        if (hasRole(auth, UserRole.PLATFORM_ADMIN)) return true;
        String userId = auth.getName();
        return userId != null && division.getHeadUserIds() != null
                && division.getHeadUserIds().contains(userId);
    }

    /** PLATFORM_ADMIN, head of the department, or head of the parent division. */
    public boolean canManageDepartmentHide(Department department, Authentication auth) {
        if (auth == null) return false;
        if (hasRole(auth, UserRole.PLATFORM_ADMIN)) return true;
        String userId = auth.getName();
        if (userId == null) return false;
        if (department.getHeadUserIds() != null && department.getHeadUserIds().contains(userId)) return true;
        if (department.getDivisionId() == null) return false;
        return orgDivisionRepository.findById(department.getDivisionId())
                .map(div -> div.getHeadUserIds() != null && div.getHeadUserIds().contains(userId))
                .orElse(false);
    }

    private void requireDivisionManage(OrgDivision division, Authentication auth) {
        if (!canManageDivisionSelection(division, auth)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the division head or a platform admin can change report selection.");
        }
    }

    private void requireDepartmentManage(Department department, Authentication auth) {
        if (!canManageDepartmentHide(department, auth)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the department head, the parent division head, or a platform admin can hide reports here.");
        }
    }

    // -------- Internals --------

    private List<IndividualReport> inheritedReportsForDepartment(Department department) {
        if (department.getDivisionId() == null) return List.of();
        List<DivisionReportSelection> selections =
                divisionReportSelectionRepository.findByDivisionId(department.getDivisionId());
        if (selections.isEmpty()) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        for (DivisionReportSelection s : selections) ids.add(s.getReportId());
        return individualReportRepository.findAllById(ids);
    }

    private static boolean hasRole(Authentication auth, UserRole role) {
        return auth.getAuthorities() != null && auth.getAuthorities().stream()
                .anyMatch(ga -> role.name().equals(ga.getAuthority()));
    }

    // -------- View records --------

    public record DivisionSelectionView(List<IndividualReport> catalog, Set<String> selectedReportIds) {}

    public record DepartmentVisibilityView(List<IndividualReport> inheritedReports, Set<String> hiddenReportIds) {}
}
