package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.org.DivisionType;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.service.UserService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffImportServiceTest {

    private static final String INST = "uvt1";
    private static final String HEADER = "Faculty,Department,Email,Last Name,First Name,Position,Id SCOPUS\n";

    @Mock private InstitutionRepository institutionRepository;
    @Mock private OrgDivisionRepository orgDivisionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private DepartmentAffiliationRepository departmentAffiliationRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserService userService;

    private StaffImportService service;

    @BeforeEach
    void setUp() {
        service = new StaffImportService(
                institutionRepository, orgDivisionRepository, departmentRepository,
                departmentAffiliationRepository, passwordEncoder, userService, 6);
        lenient().when(institutionRepository.findById(INST)).thenReturn(Optional.of(new Institution()));
        lenient().when(orgDivisionRepository.findByInstitutionId(INST)).thenReturn(List.of());
        lenient().when(departmentRepository.findByInstitutionId(INST)).thenReturn(List.of());
        lenient().when(departmentAffiliationRepository.findByUserIdInAndValidToIsNull(any())).thenReturn(List.of());
        lenient().when(orgDivisionRepository.save(any())).thenAnswer(inv -> {
            OrgDivision d = inv.getArgument(0);
            if (d.getId() == null) d.setId("div-" + d.getName());
            return d;
        });
        lenient().when(departmentRepository.save(any())).thenAnswer(inv -> {
            Department d = inv.getArgument(0);
            if (d.getId() == null) d.setId("dep-" + d.getName());
            return d;
        });
        lenient().when(userService.getUserByEmail(anyString())).thenReturn(Optional.empty());
    }

    // --- institution scope ---

    @Test
    void missingInstitutionThrows() {
        when(institutionRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.importStaffFromCsv(
                csv(HEADER + "Fac,Dept,a@b.com,Doe,John,Prof.,\n"), "missing"));
    }

    @Test
    void blankInstitutionIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.importStaffFromCsv(csv(HEADER), "  "));
    }

    // --- resolve-or-create org tree ---

    @Test
    void createsFacultyDivisionAndDepartmentWhenAbsent() throws Exception {
        StaffImportService.StaffImportResult r = service.importStaffFromCsv(
                csv(HEADER + "Facultatea de Informatica,Tehnologii Digitale,a@uvt.ro,Doe,John,Lect. Dr.,\n"), INST);

        ArgumentCaptor<OrgDivision> divCaptor = ArgumentCaptor.forClass(OrgDivision.class);
        verify(orgDivisionRepository).save(divCaptor.capture());
        assertEquals("Facultatea de Informatica", divCaptor.getValue().getName());
        assertEquals(DivisionType.FACULTY, divCaptor.getValue().getType());
        assertEquals(INST, divCaptor.getValue().getInstitutionId());

        ArgumentCaptor<Department> depCaptor = ArgumentCaptor.forClass(Department.class);
        verify(departmentRepository).save(depCaptor.capture());
        assertEquals("Tehnologii Digitale", depCaptor.getValue().getName());
        assertEquals("div-Facultatea de Informatica", depCaptor.getValue().getDivisionId());

        assertEquals(1, r.divisionsCreated);
        assertEquals(1, r.departmentsCreated);
        assertEquals(1, r.usersCreated);
    }

    @Test
    void reusesExistingDivisionAndDepartmentMatchedByNormalizedName() throws Exception {
        OrgDivision existingDiv = new OrgDivision();
        existingDiv.setId("div-existing");
        existingDiv.setName("Facultatea de Informatică");   // diacritic; CSV will use plain ASCII + trailing space
        existingDiv.setInstitutionId(INST);
        Department existingDep = new Department();
        existingDep.setId("dep-existing");
        existingDep.setDivisionId("div-existing");
        existingDep.setName("Tehnologii Digitale si Inginerie Software");
        when(orgDivisionRepository.findByInstitutionId(INST)).thenReturn(List.of(existingDiv));
        when(departmentRepository.findByInstitutionId(INST)).thenReturn(List.of(existingDep));

        StaffImportService.StaffImportResult r = service.importStaffFromCsv(csv(HEADER
                + "Facultatea de Informatica ,tehnologii digitale si inginerie software,a@uvt.ro,Doe,John,Conf.,\n"), INST);

        verify(orgDivisionRepository, never()).save(any());
        verify(departmentRepository, never()).save(any());
        assertEquals(0, r.divisionsCreated);
        assertEquals(0, r.departmentsCreated);
    }

    @Test
    void distinctDepartmentsUnderSameFacultyShareTheDivision() throws Exception {
        service.importStaffFromCsv(csv(HEADER
                + "Fac Info,Dept A,a@uvt.ro,Doe,John,Prof.,\n"
                + "Fac Info,Dept B,b@uvt.ro,Roe,Jane,Prof.,\n"), INST);

        verify(orgDivisionRepository, times(1)).save(any());   // one faculty
        verify(departmentRepository, times(2)).save(any());    // two departments
    }

    // --- user creation / update ---

    @Test
    void newUserCreatedWithEncodedPasswordRoleAndProfile() throws Exception {
        // OIDC-only: provisioned accounts get a scrambled random password (never a shared default).
        when(passwordEncoder.encode(org.mockito.ArgumentMatchers.anyString())).thenReturn("hashed");
        service.importStaffFromCsv(csv(HEADER
                + "Fac,Dept,jane@uvt.ro,Smith,Jane,Conf. Dr.,11111;22222\n"), INST);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        User u = captor.getValue();
        assertEquals("jane@uvt.ro", u.getEmail());
        assertEquals("hashed", u.getPassword());
        assertTrue(u.getRoles().contains(UserRole.RESEARCHER));
        assertEquals("Jane", u.getResearcherProfile().getFirstName());
        assertEquals("Smith", u.getResearcherProfile().getLastName());
        assertEquals(Position.CONF_UNIV, u.getResearcherProfile().getPosition());
        assertEquals(List.of("11111", "22222"), u.getResearcherProfile().getScopusId());
    }

    @Test
    void existingUserProfileUpdatedPreservingOrcid() throws Exception {
        User existing = new User();
        existing.setEmail("jane@uvt.ro");
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setOrcid("0000-0002-1825-0097");
        existing.setResearcherProfile(profile);
        when(userService.getUserByEmail("jane@uvt.ro")).thenReturn(Optional.of(existing));

        service.importStaffFromCsv(csv(HEADER + "Fac,Dept,jane@uvt.ro,Smith,Jane,Prof.,\n"), INST);

        verify(userService, never()).createUser(any());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).updateUser(eq("jane@uvt.ro"), captor.capture());
        User.ResearcherProfile saved = captor.getValue().getResearcherProfile();
        assertEquals("Jane", saved.getFirstName());
        assertEquals(Position.PROF_UNIV, saved.getPosition());
        assertEquals("0000-0002-1825-0097", saved.getOrcid());   // preserved, not wiped
    }

    @Test
    void scopusIdsNotSetWhenColumnBlank() throws Exception {
        service.importStaffFromCsv(csv(HEADER + "Fac,Dept,u@uvt.ro,Doe,John,Prof.,\n"), INST);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertTrue(captor.getValue().getResearcherProfile().getScopusId() == null
                || captor.getValue().getResearcherProfile().getScopusId().isEmpty());
    }

    // --- position parsing: both abbreviated and full Romanian forms ---

    @ParameterizedTest
    @CsvSource({
            "Lect. Dr.,                          LECT_UNIV",
            "Conf. Dr.,                          CONF_UNIV",
            "Prof. Dr.,                          PROF_UNIV",
            "Asist. Dr.,                         ASIST_UNIV",
            "Lector universitar,                 LECT_UNIV",
            "Conferenţiar universitar,           CONF_UNIV",
            "Profesor universitar,               PROF_UNIV",
            "Asistent universitar,               ASIST_UNIV",
            "Asistent de cercetare ştiinţifică,  ASIST_C"
    })
    void parsePositionMapsAbbreviatedAndFullForms(String positionField, String expectedName) throws Exception {
        Position expected = Position.valueOf(expectedName.trim());
        service.importStaffFromCsv(csv(HEADER
                + "Fac,Dept,u@uvt.ro,Doe,John," + positionField.trim() + ",\n"), INST);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertEquals(expected, captor.getValue().getResearcherProfile().getPosition());
    }

    // --- best-effort: bad rows are skipped and reported, good rows still import ---

    @Test
    void unknownPositionRowSkippedNotThrown() throws Exception {
        StaffImportService.StaffImportResult r = service.importStaffFromCsv(csv(HEADER
                + "Fac,Dept,good@uvt.ro,Doe,John,Prof.,\n"
                + "Fac,Dept,bad@uvt.ro,Roe,Jane,Wizard,\n"), INST);

        verify(userService, times(1)).createUser(any());   // only the good row
        assertEquals(1, r.usersCreated);
        assertEquals(1, r.skipped.size());
        assertTrue(r.skipped.get(0).contains("unknown position"), r.skipped.get(0));
    }

    @Test
    void invalidEmailRowSkipped() throws Exception {
        StaffImportService.StaffImportResult r = service.importStaffFromCsv(csv(HEADER
                + "Fac,Dept,not-an-email,Doe,John,Prof.,\n"), INST);
        verify(userService, never()).createUser(any());
        assertEquals(1, r.skipped.size());
    }

    @Test
    void blankRequiredFieldRowSkipped() throws Exception {
        StaffImportService.StaffImportResult r = service.importStaffFromCsv(csv(HEADER
                + "Fac,Dept,u@uvt.ro,,John,Prof.,\n"), INST);
        assertEquals(1, r.skipped.size());
        verify(userService, never()).createUser(any());
    }

    @Test
    void badHeaderThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importStaffFromCsv(csv("Faculty,Department,Email\nFac,Dept,a@b.com\n"), INST));
    }

    @Test
    void emptyFileThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.importStaffFromCsv(csv(""), INST));
    }

    @Test
    void blankLinesBetweenRowsSkipped() throws Exception {
        StaffImportService.StaffImportResult r = service.importStaffFromCsv(csv(HEADER
                + "\n" + "Fac,Dept,u@uvt.ro,Doe,John,Prof.,\n"), INST);
        assertEquals(1, r.usersCreated);
        assertTrue(r.skipped.isEmpty());
    }

    // --- department affiliation ---

    @Test
    void createsPrimaryDepartmentAffiliationToTheResolvedDepartment() throws Exception {
        service.importStaffFromCsv(csv(HEADER
                + "Fac Info,Soft Eng,jane@uvt.ro,Smith,Jane,Prof.,\n"), INST);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DepartmentAffiliation>> captor = ArgumentCaptor.forClass(List.class);
        verify(departmentAffiliationRepository).saveAll(captor.capture());
        List<DepartmentAffiliation> saved = captor.getValue();
        assertEquals(1, saved.size());
        DepartmentAffiliation aff = saved.get(0);
        assertEquals("jane@uvt.ro", aff.getUserId());
        assertEquals("dep-Soft Eng", aff.getDepartmentId());
        assertTrue(aff.isPrimary());
        assertNotNull(aff.getValidFrom());
        assertNull(aff.getValidTo());
    }

    // --- helpers ---

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "staff.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }
}
