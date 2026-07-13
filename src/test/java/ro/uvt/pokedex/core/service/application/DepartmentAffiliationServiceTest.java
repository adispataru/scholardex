package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepartmentAffiliationServiceTest {

    @Mock private DepartmentAffiliationRepository repository;

    private DepartmentAffiliationService service() {
        return new DepartmentAffiliationService(repository);
    }

    @Test
    void addCreatesCurrentAffiliationPrimaryWhenNoOtherDepartment() {
        when(repository.findByDepartmentIdAndUserIdAndValidToIsNull("dept-cs", "ana@uvt.ro"))
                .thenReturn(Optional.empty());
        when(repository.findByUserIdAndValidToIsNull("ana@uvt.ro")).thenReturn(List.of());

        assertTrue(service().addMember("dept-cs", "ana@uvt.ro"));

        ArgumentCaptor<DepartmentAffiliation> saved = ArgumentCaptor.forClass(DepartmentAffiliation.class);
        verify(repository).save(saved.capture());
        DepartmentAffiliation a = saved.getValue();
        assertEquals("dept-cs", a.getDepartmentId());
        assertEquals("ana@uvt.ro", a.getUserId());
        assertEquals(LocalDate.now(), a.getValidFrom());
        assertNull(a.getValidTo());
        assertTrue(a.isPrimary(), "first department should be primary");
    }

    @Test
    void addIsNonPrimaryForAJointAppointment() {
        when(repository.findByDepartmentIdAndUserIdAndValidToIsNull("dept-cs", "ana@uvt.ro"))
                .thenReturn(Optional.empty());
        when(repository.findByUserIdAndValidToIsNull("ana@uvt.ro"))
                .thenReturn(List.of(new DepartmentAffiliation())); // already in another department

        service().addMember("dept-cs", "ana@uvt.ro");

        ArgumentCaptor<DepartmentAffiliation> saved = ArgumentCaptor.forClass(DepartmentAffiliation.class);
        verify(repository).save(saved.capture());
        assertFalse(saved.getValue().isPrimary(), "a joint appointment must not steal the primary flag");
    }

    @Test
    void addIsIdempotentWhenAlreadyCurrent() {
        when(repository.findByDepartmentIdAndUserIdAndValidToIsNull("dept-cs", "ana@uvt.ro"))
                .thenReturn(Optional.of(new DepartmentAffiliation()));

        assertFalse(service().addMember("dept-cs", "ana@uvt.ro"));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void removeSoftClosesTheCurrentAffiliation() {
        DepartmentAffiliation current = new DepartmentAffiliation();
        current.setDepartmentId("dept-cs");
        current.setUserId("ana@uvt.ro");
        when(repository.findByDepartmentIdAndUserIdAndValidToIsNull("dept-cs", "ana@uvt.ro"))
                .thenReturn(Optional.of(current));

        assertTrue(service().removeMember("dept-cs", "ana@uvt.ro"));

        assertEquals(LocalDate.now(), current.getValidTo(), "removal end-dates rather than deletes");
        verify(repository).save(current);
    }

    @Test
    void removeIsANoOpWhenNotAMember() {
        when(repository.findByDepartmentIdAndUserIdAndValidToIsNull("dept-cs", "ghost@uvt.ro"))
                .thenReturn(Optional.empty());

        assertFalse(service().removeMember("dept-cs", "ghost@uvt.ro"));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
