package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates everything a supervisor "owns": divisions and departments they head directly,
 * plus the groups under them (implicit) and groups where they appear in
 * {@code supervisorUserIds} (explicit). Mirrors the access rules in
 * {@link ro.uvt.pokedex.core.service.security.GroupAccessService}.
 *
 * <p>The returned view also carries id→name lookup maps for institutions, divisions, and
 * departments so the template can render human-readable labels without holding refs to
 * every repository.
 */
@Service
@RequiredArgsConstructor
public class SupervisorWorkspaceService {

    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentRepository departmentRepository;
    private final GroupRepository groupRepository;
    private final InstitutionRepository institutionRepository;

    public SupervisorWorkspaceView buildView(String userId) {
        if (userId == null || userId.isBlank()) {
            return SupervisorWorkspaceView.empty();
        }

        List<OrgDivision> headedDivisions = new ArrayList<>(
                orgDivisionRepository.findByHeadUserIdsContaining(userId));
        List<Department> headedDepartments = new ArrayList<>(
                departmentRepository.findByHeadUserIdsContaining(userId));

        // Departments inside divisions the user heads become implicit responsibilities too.
        if (!headedDivisions.isEmpty()) {
            Set<String> divisionIds = new HashSet<>();
            for (OrgDivision d : headedDivisions) divisionIds.add(d.getId());
            Set<String> seen = new HashSet<>();
            for (Department d : headedDepartments) seen.add(d.getId());
            for (Department d : departmentRepository.findByDivisionIdIn(divisionIds)) {
                if (seen.add(d.getId())) headedDepartments.add(d);
            }
        }

        // Groups: explicit supervisor entries + everything under the headed departments.
        Map<String, Group> byId = new LinkedHashMap<>();
        for (Group g : groupRepository.findBySupervisorUserIdsContaining(userId)) {
            byId.putIfAbsent(g.getId(), g);
        }
        if (!headedDepartments.isEmpty()) {
            Set<String> deptIds = new HashSet<>();
            for (Department d : headedDepartments) deptIds.add(d.getId());
            for (Group g : groupRepository.findByDepartmentIdsIn(deptIds)) {
                byId.putIfAbsent(g.getId(), g);
            }
        }

        headedDivisions.sort(Comparator.comparing(d -> nullSafe(d.getName())));
        headedDepartments.sort(Comparator.comparing(d -> nullSafe(d.getName())));
        List<Group> groups = new ArrayList<>(byId.values());
        groups.sort(Comparator.comparing(g -> nullSafe(g.getName())));

        return new SupervisorWorkspaceView(
                headedDivisions,
                headedDepartments,
                groups,
                buildInstitutionNames(headedDivisions, headedDepartments, groups),
                buildDivisionNames(headedDepartments),
                buildDepartmentNames(groups, headedDepartments));
    }

    private Map<String, String> buildInstitutionNames(
            List<OrgDivision> divisions, List<Department> departments, List<Group> groups) {
        Set<String> ids = new HashSet<>();
        for (OrgDivision d : divisions) if (d.getInstitutionId() != null) ids.add(d.getInstitutionId());
        for (Department d : departments) if (d.getInstitutionId() != null) ids.add(d.getInstitutionId());
        for (Group g : groups) if (g.getInstitutionId() != null) ids.add(g.getInstitutionId());
        if (ids.isEmpty()) return Collections.emptyMap();
        Map<String, String> names = new HashMap<>();
        for (Institution institution : institutionRepository.findAllById(ids)) {
            names.put(institution.getId(), institution.getName());
        }
        return names;
    }

    /** Pulls division names referenced by the user's department list (covers division-of-departments labels). */
    private Map<String, String> buildDivisionNames(List<Department> departments) {
        Set<String> ids = new HashSet<>();
        for (Department d : departments) if (d.getDivisionId() != null) ids.add(d.getDivisionId());
        if (ids.isEmpty()) return Collections.emptyMap();
        Map<String, String> names = new HashMap<>();
        for (OrgDivision division : orgDivisionRepository.findAllById(ids)) {
            names.put(division.getId(), division.getName());
        }
        return names;
    }

    /** Pulls department names referenced by the user's groups, plus the heads' own departments for completeness. */
    private Map<String, String> buildDepartmentNames(List<Group> groups, List<Department> headed) {
        Set<String> ids = new HashSet<>();
        for (Group g : groups) {
            if (g.getDepartmentIds() != null) ids.addAll(g.getDepartmentIds());
        }
        for (Department d : headed) ids.add(d.getId());
        ids.remove(null);
        if (ids.isEmpty()) return Collections.emptyMap();
        Map<String, String> names = new HashMap<>();
        for (Department department : departmentRepository.findAllById(ids)) {
            names.put(department.getId(), department.getName());
        }
        return names;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    public record SupervisorWorkspaceView(
            List<OrgDivision> divisions,
            List<Department> departments,
            List<Group> groups,
            Map<String, String> institutionNames,
            Map<String, String> divisionNames,
            Map<String, String> departmentNames
    ) {
        public static SupervisorWorkspaceView empty() {
            return new SupervisorWorkspaceView(
                    List.of(), List.of(), List.of(),
                    Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        }

        public boolean isEmpty() {
            return divisions.isEmpty() && departments.isEmpty() && groups.isEmpty();
        }
    }
}
