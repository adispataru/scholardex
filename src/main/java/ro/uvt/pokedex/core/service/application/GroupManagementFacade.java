package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.GroupEditViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupListViewModel;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GroupManagementFacade {
    private final GroupRepository groupRepository;
    private final DomainRepository domainRepository;
    private final InstitutionRepository institutionRepository;
    private final UserService userService;

    public GroupListViewModel buildGroupListView() {
        return new GroupListViewModel(
                groupRepository.findAll(),
                domainRepository.findAll(),
                institutionRepository.findAll(),
                userService.findUsersWithResearcherProfile(),
                new Group()
        );
    }

    public GroupEditViewModel buildGroupEditView(String groupId) {
        Group group = groupRepository.findById(groupId).orElse(null);
        return new GroupEditViewModel(
                group,
                domainRepository.findAll(),
                institutionRepository.findAll(),
                userService.findUsersWithResearcherProfile()
        );
    }

    public void createGroup(Group group) {
        groupRepository.save(group);
    }

    public void updateGroup(Group group) {
        groupRepository.save(group);
    }

    public void deleteGroup(String groupId) {
        groupRepository.deleteById(groupId);
    }

    public int addMembersToGroup(String groupId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return 0;
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) return 0;
        List<String> existing = group.getMemberIds() != null ? group.getMemberIds() : new java.util.ArrayList<>();
        int added = 0;
        for (String uid : userIds) {
            if (!existing.contains(uid)) { existing.add(uid); added++; }
        }
        group.setMemberIds(existing);
        groupRepository.save(group);
        return added;
    }
}
