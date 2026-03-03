package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.ResearcherRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupManagementFacadeTest {

    @Mock
    private GroupRepository groupRepository;
    @Mock
    private DomainRepository domainRepository;
    @Mock
    private InstitutionRepository institutionRepository;
    @Mock
    private ResearcherRepository researcherRepository;

    @InjectMocks
    private GroupManagementFacade facade;

    @Test
    void buildGroupListViewReturnsAllRequiredData() {
        Group group = new Group();
        Domain domain = new Domain();
        Institution institution = new Institution();
        Researcher researcher = new Researcher();

        when(groupRepository.findAll()).thenReturn(List.of(group));
        when(domainRepository.findAll()).thenReturn(List.of(domain));
        when(institutionRepository.findAll()).thenReturn(List.of(institution));
        when(researcherRepository.findAll()).thenReturn(List.of(researcher));

        var vm = facade.buildGroupListView();

        assertEquals(1, vm.groups().size());
        assertEquals(1, vm.allDomains().size());
        assertEquals(1, vm.affiliations().size());
        assertEquals(1, vm.allResearchers().size());
        assertNotNull(vm.group());
    }

    @Test
    void buildGroupEditViewMapsGroupAndReferenceData() {
        Group group = new Group();
        group.setName("G1");
        Domain domain = new Domain();
        Institution institution = new Institution();
        Researcher researcher = new Researcher();

        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(domainRepository.findAll()).thenReturn(List.of(domain));
        when(institutionRepository.findAll()).thenReturn(List.of(institution));
        when(researcherRepository.findAll()).thenReturn(List.of(researcher));

        var vm = facade.buildGroupEditView("g1");

        assertNotNull(vm.group());
        assertEquals("G1", vm.group().getName());
        assertEquals(1, vm.domains().size());
        assertEquals(1, vm.affiliations().size());
        assertEquals(1, vm.allResearchers().size());
    }

    @Test
    void createGroupSavesEntity() {
        Group group = new Group();

        facade.createGroup(group);

        verify(groupRepository, times(1)).save(group);
    }

    @Test
    void updateGroupSavesEntity() {
        Group group = new Group();

        facade.updateGroup(group);

        verify(groupRepository, times(1)).save(group);
    }

    @Test
    void deleteGroupDeletesById() {
        facade.deleteGroup("g1");

        verify(groupRepository, times(1)).deleteById("g1");
    }
}
