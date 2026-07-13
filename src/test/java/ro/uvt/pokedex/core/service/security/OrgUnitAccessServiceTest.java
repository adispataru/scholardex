package ro.uvt.pokedex.core.service.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgUnitAccessServiceTest {

    @Mock private DepartmentRepository departmentRepository;
    @Mock private OrgDivisionRepository orgDivisionRepository;

    private OrgUnitAccessService service() {
        return new OrgUnitAccessService(departmentRepository, orgDivisionRepository);
    }

    @Test
    void departmentHeadCanManage() {
        Department dept = department("dept-cs", "div-fmi", List.of("ana@uvt.ro"));
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(dept));

        assertTrue(service().canManageDepartment("dept-cs", auth("ana@uvt.ro", "SUPERVISOR")));
    }

    @Test
    void divisionHeadCanManageADepartmentBeneath() {
        Department dept = department("dept-cs", "div-fmi", List.of());
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(dept));
        when(orgDivisionRepository.findById("div-fmi"))
                .thenReturn(Optional.of(division("div-fmi", List.of("dean@uvt.ro"))));

        assertTrue(service().canManageDepartment("dept-cs", auth("dean@uvt.ro", "SUPERVISOR")));
    }

    @Test
    void unrelatedSupervisorCannotManage() {
        Department dept = department("dept-cs", "div-fmi", List.of("ana@uvt.ro"));
        when(departmentRepository.findById("dept-cs")).thenReturn(Optional.of(dept));
        when(orgDivisionRepository.findById("div-fmi"))
                .thenReturn(Optional.of(division("div-fmi", List.of("dean@uvt.ro"))));

        assertFalse(service().canManageDepartment("dept-cs", auth("stranger@uvt.ro", "SUPERVISOR")));
    }

    @Test
    void platformAdminBypassesEveryCheck() {
        // No repository stubbing needed — admin short-circuits before any lookup.
        assertTrue(service().canManageDepartment("dept-cs", auth("admin@uvt.ro", "PLATFORM_ADMIN")));
    }

    @Test
    void unknownDepartmentIsDenied() {
        when(departmentRepository.findById("ghost")).thenReturn(Optional.empty());

        assertFalse(service().canManageDepartment("ghost", auth("ana@uvt.ro", "SUPERVISOR")));
    }

    @Test
    void unauthenticatedIsDenied() {
        assertFalse(service().canManageDepartment("dept-cs", null));
    }

    private Authentication auth(String name, String authority) {
        return new TestingAuthenticationToken(name, null, authority);
    }

    private Department department(String id, String divisionId, List<String> heads) {
        Department d = new Department();
        d.setId(id);
        d.setDivisionId(divisionId);
        d.setHeadUserIds(new java.util.ArrayList<>(heads));
        return d;
    }

    private OrgDivision division(String id, List<String> heads) {
        OrgDivision d = new OrgDivision();
        d.setId(id);
        d.setHeadUserIds(new java.util.ArrayList<>(heads));
        return d;
    }
}
