package ro.uvt.pokedex.core.service.user;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes a deactivated user from {@code OrgDivision.headUserIds},
 * {@code Department.headUserIds}, and {@code Group.supervisorUserIds} on
 * {@link UserDeactivatedEvent}.
 *
 * <p>Locked users keep their User record (so logins fail with a clear "locked" message), but
 * leaving them in the head lists silently grants them implicit supervision via {@code
 * GroupAccessService} — which checks {@code authentication.getName()}, not lock state. Pruning
 * eliminates that silent path. Deleted users are pruned for the same reason: stale lists
 * resolve to dead emails.
 */
@Component
@RequiredArgsConstructor
public class OrgHeadPruningListener {

    private static final Logger log = LoggerFactory.getLogger(OrgHeadPruningListener.class);

    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentRepository departmentRepository;
    private final GroupRepository groupRepository;

    @EventListener
    public void onUserDeactivated(UserDeactivatedEvent event) {
        String userId = event.userId();
        if (userId == null || userId.isBlank()) return;

        int divisionsPruned = pruneDivisions(userId);
        int departmentsPruned = pruneDepartments(userId);
        int groupsPruned = pruneGroups(userId);

        if (divisionsPruned + departmentsPruned + groupsPruned > 0) {
            log.info("Pruned deactivated user {} ({}): divisions={}, departments={}, groups={}",
                    userId, event.reason(), divisionsPruned, departmentsPruned, groupsPruned);
        }
    }

    private int pruneDivisions(String userId) {
        List<OrgDivision> hits = orgDivisionRepository.findByHeadUserIdsContaining(userId);
        if (hits.isEmpty()) return 0;
        List<OrgDivision> toSave = new ArrayList<>();
        for (OrgDivision d : hits) {
            if (d.getHeadUserIds() != null && d.getHeadUserIds().remove(userId)) toSave.add(d);
        }
        if (!toSave.isEmpty()) orgDivisionRepository.saveAll(toSave);
        return toSave.size();
    }

    private int pruneDepartments(String userId) {
        List<Department> hits = departmentRepository.findByHeadUserIdsContaining(userId);
        if (hits.isEmpty()) return 0;
        List<Department> toSave = new ArrayList<>();
        for (Department d : hits) {
            if (d.getHeadUserIds() != null && d.getHeadUserIds().remove(userId)) toSave.add(d);
        }
        if (!toSave.isEmpty()) departmentRepository.saveAll(toSave);
        return toSave.size();
    }

    private int pruneGroups(String userId) {
        List<Group> hits = groupRepository.findBySupervisorUserIdsContaining(userId);
        if (hits.isEmpty()) return 0;
        List<Group> toSave = new ArrayList<>();
        for (Group g : hits) {
            if (g.getSupervisorUserIds() != null && g.getSupervisorUserIds().remove(userId)) toSave.add(g);
        }
        if (!toSave.isEmpty()) groupRepository.saveAll(toSave);
        return toSave.size();
    }
}
