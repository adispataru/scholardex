package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.ResearcherRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.application.model.GroupEditViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupListViewModel;

@Service
@RequiredArgsConstructor
public class GroupManagementFacade {
    private final GroupRepository groupRepository;
    private final DomainRepository domainRepository;
    private final InstitutionRepository institutionRepository;
    private final ResearcherRepository researcherRepository;

    public GroupListViewModel buildGroupListView() {
        return new GroupListViewModel(
                groupRepository.findAll(),
                domainRepository.findAll(),
                institutionRepository.findAll(),
                researcherRepository.findAll(),
                new Group()
        );
    }

    public GroupEditViewModel buildGroupEditView(String groupId) {
        Group group = groupRepository.findById(groupId).orElse(null);
        return new GroupEditViewModel(
                group,
                domainRepository.findAll(),
                institutionRepository.findAll(),
                researcherRepository.findAll()
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
}
