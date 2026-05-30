package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DivisionType;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused on the new OrgDivision / Department CRUD added in the hierarchy work.
 * Covers cleanIdList, user-existence validation, denormalization of institutionId,
 * and the delete-guard for divisions with descendants.
 */
@ExtendWith(MockitoExtension.class)
class AdminCatalogFacadeOrgCrudTest {

    @Mock private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock private ArtisticEventRepository artisticEventRepository;
    @Mock private CoreConferenceRankingRepository coreConferenceRankingRepository;
    @Mock private IndicatorRepository indicatorRepository;
    @Mock private DomainRepository domainRepository;
    @Mock private InstitutionRepository institutionRepository;
    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private UserRepository userRepository;
    @Mock private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Mock private PostgresWosRankingDetailsReadPort postgresWosRankingDetailsReadPort;

    @InjectMocks
    private AdminCatalogFacade facade;

    // --- OrgDivision ---

    @Test
    void saveOrgDivisionStripsBlanksAndDuplicatesFromHeads() {
        when(userRepository.findAllById(any())).thenReturn(List.of(user("a@uvt.ro"), user("b@uvt.ro")));
        when(orgDivisionRepository.save(any(OrgDivision.class))).thenAnswer(inv -> inv.getArgument(0));

        OrgDivision d = new OrgDivision();
        d.setId("div-1");
        d.setHeadUserIds(new ArrayList<>(java.util.Arrays.asList("a@uvt.ro", "", "a@uvt.ro", null, "b@uvt.ro")));

        OrgDivision saved = facade.saveOrgDivision(d);

        assertEquals(List.of("a@uvt.ro", "b@uvt.ro"), saved.getHeadUserIds());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void saveOrgDivisionRejectsUnknownHeadEmails() {
        // Only "a@uvt.ro" exists; "ghost@uvt.ro" doesn't resolve.
        when(userRepository.findAllById(any())).thenReturn(List.of(user("a@uvt.ro")));

        OrgDivision d = new OrgDivision();
        d.setHeadUserIds(new ArrayList<>(List.of("a@uvt.ro", "ghost@uvt.ro")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> facade.saveOrgDivision(d));
        assertTrue(ex.getMessage().contains("ghost@uvt.ro"));
        verify(orgDivisionRepository, never()).save(any());
    }

    @Test
    void saveOrgDivisionWithEmptyHeadsSkipsValidationAndStillPersists() {
        when(orgDivisionRepository.save(any(OrgDivision.class))).thenAnswer(inv -> inv.getArgument(0));

        OrgDivision d = new OrgDivision();
        d.setName("FMI");
        d.setType(DivisionType.FACULTY);

        OrgDivision saved = facade.saveOrgDivision(d);

        assertTrue(saved.getHeadUserIds().isEmpty());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void saveOrgDivisionPreservesExistingCreatedAtOnUpdate() {
        when(orgDivisionRepository.save(any(OrgDivision.class))).thenAnswer(inv -> inv.getArgument(0));

        OrgDivision d = new OrgDivision();
        java.time.Instant original = java.time.Instant.parse("2020-01-01T00:00:00Z");
        d.setCreatedAt(original);

        OrgDivision saved = facade.saveOrgDivision(d);

        assertEquals(original, saved.getCreatedAt());
        assertNotEquals(original, saved.getUpdatedAt());
    }

    @Test
    void deleteOrgDivisionWithDescendantDepartmentsThrowsAndDoesNotDelete() {
        when(departmentRepository.findByDivisionId("div-1"))
                .thenReturn(List.of(new Department()));

        assertThrows(IllegalStateException.class, () -> facade.deleteOrgDivision("div-1"));
        verify(orgDivisionRepository, never()).deleteById(any());
    }

    @Test
    void deleteOrgDivisionWithNoDescendantsDeletes() {
        when(departmentRepository.findByDivisionId("div-1")).thenReturn(List.of());

        facade.deleteOrgDivision("div-1");

        verify(orgDivisionRepository).deleteById("div-1");
    }

    // --- Department ---

    @Test
    void saveDepartmentRebuildsInstitutionIdFromParentDivisionEvenIfInboundValueLies() {
        OrgDivision parent = new OrgDivision();
        parent.setId("div-fmi");
        parent.setInstitutionId("inst-uvt");
        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(parent));
        when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

        Department dept = new Department();
        dept.setName("CS");
        dept.setDivisionId("div-fmi");
        dept.setInstitutionId("WRONG-VALUE-FROM-CLIENT");

        Department saved = facade.saveDepartment(dept);

        assertEquals("inst-uvt", saved.getInstitutionId(),
                "institutionId must be rebuilt from the parent division, ignoring client input");
    }

    @Test
    void saveDepartmentRejectsMissingDivisionId() {
        Department dept = new Department();
        dept.setName("CS");

        assertThrows(IllegalArgumentException.class, () -> facade.saveDepartment(dept));
    }

    @Test
    void saveDepartmentRejectsUnknownParentDivision() {
        when(orgDivisionRepository.findById("missing")).thenReturn(Optional.empty());

        Department dept = new Department();
        dept.setDivisionId("missing");

        assertThrows(IllegalArgumentException.class, () -> facade.saveDepartment(dept));
    }

    @Test
    void saveDepartmentStripsBlanksAndDuplicatesFromHeadsAndValidatesUsers() {
        OrgDivision parent = new OrgDivision();
        parent.setId("div-fmi");
        parent.setInstitutionId("inst-uvt");
        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(parent));
        when(userRepository.findAllById(any())).thenReturn(List.of(user("a@uvt.ro")));
        when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

        Department dept = new Department();
        dept.setDivisionId("div-fmi");
        dept.setHeadUserIds(new ArrayList<>(List.of("", "a@uvt.ro", "a@uvt.ro")));

        Department saved = facade.saveDepartment(dept);

        assertEquals(List.of("a@uvt.ro"), saved.getHeadUserIds());
    }

    @Test
    void saveDepartmentRejectsUnknownHeadEmails() {
        OrgDivision parent = new OrgDivision();
        parent.setId("div-fmi");
        parent.setInstitutionId("inst-uvt");
        when(orgDivisionRepository.findById("div-fmi")).thenReturn(Optional.of(parent));
        when(userRepository.findAllById(any())).thenReturn(List.of()); // none exist

        Department dept = new Department();
        dept.setDivisionId("div-fmi");
        dept.setHeadUserIds(new ArrayList<>(List.of("ghost@uvt.ro")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> facade.saveDepartment(dept));
        assertTrue(ex.getMessage().contains("ghost@uvt.ro"));
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void listDepartmentsReturnsSortedByName() {
        Department alpha = new Department(); alpha.setName("Alpha");
        Department beta = new Department(); beta.setName("Beta");
        when(departmentRepository.findAll()).thenReturn(List.of(beta, alpha));

        List<Department> result = facade.listDepartments();

        assertEquals(List.of("Alpha", "Beta"), result.stream().map(Department::getName).toList());
    }

    @Test
    void listOrgDivisionsReturnsSortedByName() {
        OrgDivision a = new OrgDivision(); a.setName("Alpha");
        OrgDivision b = new OrgDivision(); b.setName("Beta");
        when(orgDivisionRepository.findAll()).thenReturn(List.of(b, a));

        assertEquals(List.of("Alpha", "Beta"),
                facade.listOrgDivisions().stream().map(OrgDivision::getName).toList());
    }

    private static User user(String email) {
        User u = new User();
        u.setEmail(email);
        return u;
    }
}
