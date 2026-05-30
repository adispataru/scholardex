package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.DepartmentOption;
import ro.uvt.pokedex.core.service.application.model.GroupEditViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupListViewModel;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GroupManagementFacade {
    private final GroupRepository groupRepository;
    private final DomainRepository domainRepository;
    private final InstitutionRepository institutionRepository;
    private final DepartmentRepository departmentRepository;
    private final OrgDivisionRepository orgDivisionRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final GroupMembershipService groupMembershipService;

    public GroupListViewModel buildGroupListView() {
        List<Group> groups = groupRepository.findAll();
        List<ro.uvt.pokedex.core.model.reporting.Domain> allDomains = domainRepository.findAll();
        List<DepartmentOption> departmentOptions = buildDepartmentOptions();

        Map<String, ro.uvt.pokedex.core.model.reporting.Domain> domainsById = new HashMap<>();
        for (var domain : allDomains) {
            if (domain.getName() != null) domainsById.put(domain.getName(), domain);
        }
        Map<String, DepartmentOption> departmentsById = new HashMap<>();
        for (DepartmentOption opt : departmentOptions) {
            departmentsById.put(opt.id(), opt);
        }
        Map<String, Integer> memberCountByGroupId = new HashMap<>();
        for (Group g : groups) {
            memberCountByGroupId.put(g.getId(),
                    groupMembershipService.listCurrentMemberUserIds(g.getId()).size());
        }

        return new GroupListViewModel(
                groups,
                allDomains,
                institutionRepository.findAll(),
                departmentOptions,
                userService.findUsersWithResearcherProfile(),
                domainsById,
                departmentsById,
                memberCountByGroupId,
                new Group()
        );
    }

    public GroupEditViewModel buildGroupEditView(String groupId) {
        Group group = groupRepository.findById(groupId).orElse(null);
        List<String> currentMembers = group == null
                ? List.of()
                : groupMembershipService.listCurrentMemberUserIds(group.getId());
        return new GroupEditViewModel(
                group,
                domainRepository.findAll(),
                institutionRepository.findAll(),
                buildDepartmentOptions(),
                userService.findUsersWithResearcherProfile(),
                currentMembers
        );
    }

    public Group createGroup(Group group, Collection<String> memberUserIds) {
        applyDenormalizedFields(group);
        group.setSupervisorUserIds(IdListCleaner.clean(group.getSupervisorUserIds()));
        requireKnownUsers(group.getSupervisorUserIds());
        group.setDomainIds(IdListCleaner.clean(group.getDomainIds()));
        group.setDepartmentIds(IdListCleaner.clean(group.getDepartmentIds()));
        Instant now = Instant.now();
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        Group saved = groupRepository.save(group);
        if (memberUserIds != null && !memberUserIds.isEmpty()) {
            groupMembershipService.syncMembers(saved.getId(), memberUserIds);
        }
        return saved;
    }

    public Group updateGroup(Group group, Collection<String> memberUserIds) {
        applyDenormalizedFields(group);
        group.setSupervisorUserIds(IdListCleaner.clean(group.getSupervisorUserIds()));
        requireKnownUsers(group.getSupervisorUserIds());
        group.setDomainIds(IdListCleaner.clean(group.getDomainIds()));
        group.setDepartmentIds(IdListCleaner.clean(group.getDepartmentIds()));
        group.setUpdatedAt(Instant.now());
        Group saved = groupRepository.save(group);
        groupMembershipService.syncMembers(saved.getId(),
                memberUserIds == null ? Collections.emptyList() : memberUserIds);
        return saved;
    }

    public void deleteGroup(String groupId) {
        groupRepository.deleteById(groupId);
    }

    public int addMembersToGroup(String groupId, List<String> userIds) {
        return groupMembershipService.addMembers(groupId, userIds);
    }

    /**
     * Rebuilds {@code Group.institutionId} from the assigned departments. Cross-institution
     * joint labs are rejected (no group may span institutions). Trusts only the database;
     * the {@code institutionId} on the inbound group is ignored.
     */
    private void applyDenormalizedFields(Group group) {
        List<String> departmentIds = group.getDepartmentIds();
        if (departmentIds == null || departmentIds.isEmpty()) {
            group.setInstitutionId(null);
            return;
        }
        List<Department> departments = departmentRepository.findByIdIn(departmentIds);
        Set<String> institutionIds = new HashSet<>();
        for (Department dept : departments) {
            if (dept.getInstitutionId() != null) {
                institutionIds.add(dept.getInstitutionId());
            }
        }
        if (institutionIds.isEmpty()) {
            throw new IllegalArgumentException("Departments not found or missing institutionId: " + departmentIds);
        }
        if (institutionIds.size() > 1) {
            throw new IllegalArgumentException(
                    "Group spans multiple institutions, which is not supported: " + institutionIds);
        }
        group.setInstitutionId(institutionIds.iterator().next());
    }

    private void requireKnownUsers(List<String> emails) {
        if (emails == null || emails.isEmpty()) return;
        java.util.Set<String> found = new java.util.HashSet<>();
        for (var u : userRepository.findAllById(emails)) found.add(u.getEmail());
        List<String> unknown = new java.util.ArrayList<>();
        for (String email : emails) if (!found.contains(email)) unknown.add(email);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Group supervisors include unknown users: " + unknown);
        }
    }

    private List<DepartmentOption> buildDepartmentOptions() {
        List<Department> departments = departmentRepository.findAll();
        if (departments.isEmpty()) return List.of();
        Map<String, OrgDivision> divisionsById = new HashMap<>();
        for (OrgDivision division : orgDivisionRepository.findAll()) {
            divisionsById.put(division.getId(), division);
        }
        Map<String, Institution> institutionsById = new HashMap<>();
        for (Institution institution : institutionRepository.findAll()) {
            institutionsById.put(institution.getId(), institution);
        }
        List<DepartmentOption> options = new java.util.ArrayList<>();
        for (Department department : departments) {
            OrgDivision division = divisionsById.get(department.getDivisionId());
            Institution institution = department.getInstitutionId() == null
                    ? null
                    : institutionsById.get(department.getInstitutionId());
            options.add(new DepartmentOption(
                    department.getId(),
                    department.getName(),
                    division == null ? null : division.getName(),
                    division == null || division.getType() == null ? null : division.getType().name(),
                    department.getInstitutionId(),
                    institution == null ? null : institution.getName()
            ));
        }
        options.sort(Comparator
                .comparing((DepartmentOption o) -> o.institutionShortName() == null ? "" : o.institutionShortName())
                .thenComparing(o -> o.divisionName() == null ? "" : o.divisionName())
                .thenComparing(o -> o.name() == null ? "" : o.name()));
        return options;
    }
}
