package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.registry.RegistryDomainExperts;
import ro.uvt.pokedex.core.model.registry.RegistryEntry;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.repository.RegistryDomainExpertsRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H144 — an admin adds the domains whose experts rank, beyond the ones the registries already name. */
class RegistryExpertsAdminServiceTest {

    private final RegistryDomainExpertsRepository repository = mock(RegistryDomainExpertsRepository.class);
    private final RegistryStores stores = mock(RegistryStores.class);
    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final OrgDivisionRepository divisions = mock(OrgDivisionRepository.class);
    private final RegistryExpertsAdminService service = new RegistryExpertsAdminService(repository, stores, departments, divisions);

    @Test
    void aNewDomainIsAddedOnceAndListedWithTheRegistriesOwn() {
        when(repository.existsById("Științe ale educației")).thenReturn(false);
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(departments.findAll()).thenReturn(List.of());

        assertEquals(Optional.of("Științe ale educației"), service.addDomain("  Științe   ale educației ", "admin@uvt.ro"));
        ArgumentCaptor<RegistryDomainExperts> saved = ArgumentCaptor.forClass(RegistryDomainExperts.class);
        verify(repository).save(saved.capture());
        assertEquals("Științe ale educației", saved.getValue().getDomain());
        assertEquals("admin@uvt.ro", saved.getValue().getUpdatedBy());

        when(repository.existsById("Muzică")).thenReturn(true);
        assertTrue(service.addDomain("Muzică", "admin@uvt.ro").isEmpty(), "already there");
        assertTrue(service.addDomain("   ", "admin@uvt.ro").isEmpty());
        assertTrue(service.addDomain("x".repeat(81), "admin@uvt.ro").isEmpty());
    }

    @Test
    void thePageListsTheDomainsOfEveryRegistryAndTheConfiguredOnes() {
        RegistryEntry conference = RegistryEntry.of(RegistryKind.SCIENTIFIC_EVENT);
        conference.setDomainId("Psihologie");
        when(stores.all(any())).thenReturn(List.of());
        when(stores.all(RegistryKind.SCIENTIFIC_EVENT)).thenReturn(List.of(conference));
        RegistryDomainExperts music = new RegistryDomainExperts();
        music.setDomain("Muzică");
        when(repository.findAll()).thenReturn(List.of(music));
        Department dep = new Department();
        dep.setId("d1");
        dep.setName("Departamentul de Psihologie");
        when(departments.findAll()).thenReturn(List.of(dep));
        when(divisions.findAll()).thenReturn(List.of());

        var page = service.page();
        assertEquals(List.of("Muzică", "Psihologie"), page.domains().stream().map(RegistryExpertsAdminService.DomainView::domain).toList());
        assertEquals("Departamentul de Psihologie", page.departments().getFirst().label());
    }

    @Test
    void savingKeepsOnlyKnownDepartmentsAndEmailAddresses() {
        when(repository.findById("Muzică")).thenReturn(Optional.empty());
        Department dep = new Department();
        dep.setId("d1");
        when(departments.findAll()).thenReturn(List.of(dep));
        service.save("Muzică", List.of("d1", "ghost"), "Critic@UVT.ro, not-an-email\nexpert@uvt.ro", "admin@uvt.ro");
        ArgumentCaptor<RegistryDomainExperts> saved = ArgumentCaptor.forClass(RegistryDomainExperts.class);
        verify(repository).save(saved.capture());
        assertEquals(List.of("d1"), saved.getValue().getDepartmentIds());
        assertEquals(List.of("critic@uvt.ro", "expert@uvt.ro"), saved.getValue().getExpertEmails());
        verify(repository, never()).deleteById(any());
    }
}
