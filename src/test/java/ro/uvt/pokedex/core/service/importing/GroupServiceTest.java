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
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.GroupMembershipService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    private static final String INST = "uvt1";
    private static final String DEPT = "d1";

    @Mock private GroupRepository groupRepository;
    @Mock private InstitutionRepository institutionRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private DepartmentAffiliationRepository departmentAffiliationRepository;
    @Mock private GroupMembershipService groupMembershipService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserService userService;

    private GroupService groupService;
    private Institution uvt;

    @BeforeEach
    void setUp() {
        groupService = new GroupService(
                groupRepository, institutionRepository, departmentRepository,
                departmentAffiliationRepository, groupMembershipService,
                passwordEncoder, userService, "2025", 6
        );
        uvt = new Institution();
        uvt.setName("UVT");
        Department dept = new Department();
        dept.setId(DEPT);
        dept.setInstitutionId(INST);
        lenient().when(institutionRepository.findById(INST)).thenReturn(Optional.of(uvt));
        lenient().when(departmentRepository.findByInstitutionId(INST)).thenReturn(List.of(dept));
        lenient().when(departmentAffiliationRepository.findByUserIdInAndValidToIsNull(any()))
                .thenReturn(List.of());
        lenient().when(groupRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<Group> groups = inv.getArgument(0);
            java.util.List<Group> saved = new java.util.ArrayList<>();
            for (Group g : groups) {
                if (g.getId() == null) g.setId("g-" + g.getName());
                saved.add(g);
            }
            return saved;
        });
    }

    // --- institution scope ---

    @Test
    void missingInstitutionThrows() {
        when(institutionRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> groupService.importGroupsFromCsv(
                csv(header() + "G1,a@b.com,Doe,John,Prof.," + DEPT + "\n"), "missing"));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void blankInstitutionIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv(header()), "  "));
    }

    // --- group field propagation ---

    @Test
    void importGroupsSetsGroupNameDescriptionAndInstitution() throws Exception {
        stubNewUser();
        groupService.importGroupsFromCsv(
                csv(header() + "ResearchLab,user@uvt.ro,Doe,John,Prof.," + DEPT + "\n"), INST);

        Group saved = captureFirstSavedGroup();
        assertEquals("ResearchLab", saved.getName());
        assertEquals("Imported from CSV", saved.getDescription());
        assertEquals(INST, saved.getInstitutionId());
        assertEquals(List.of(DEPT), saved.getDepartmentIds());
    }

    @Test
    void importGroupsAddsMembersViaMembershipService() throws Exception {
        stubNewUser();
        groupService.importGroupsFromCsv(
                csv(header() + "G1,user@uvt.ro,Doe,John,Prof.," + DEPT + "\n"), INST);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> userIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(groupMembershipService).addMembers(eq("g-G1"), userIdsCaptor.capture());
        assertEquals(List.of("user@uvt.ro"), userIdsCaptor.getValue());
    }

    @Test
    void importGroupsAccumulatesDistinctMembers() throws Exception {
        when(userService.getUserByEmail(any())).thenReturn(Optional.empty());
        groupService.importGroupsFromCsv(csv(header()
                + "G1,alice@uvt.ro,Alice,A,Prof.," + DEPT + "\n"
                + "G1,bob@uvt.ro,Bob,B,Prof.," + DEPT + "\n"), INST);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> userIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(groupMembershipService).addMembers(eq("g-G1"), userIdsCaptor.capture());
        assertEquals(List.of("alice@uvt.ro", "bob@uvt.ro"), userIdsCaptor.getValue());
    }

    // --- new user creation ---

    @Test
    void newUserCreatedWithEncodedPasswordAndResearcherRole() throws Exception {
        when(passwordEncoder.encode("2025")).thenReturn("hashed");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(
                csv(header() + "G1,user@uvt.ro,Doe,John,Conf.," + DEPT + "\n"), INST);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        User created = captor.getValue();
        assertEquals("user@uvt.ro", created.getEmail());
        assertEquals("hashed", created.getPassword());
        assertTrue(created.getRoles().contains(UserRole.RESEARCHER));
    }

    @Test
    void newUserResearcherProfileContainsFirstNameLastNameAndPosition() throws Exception {
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(
                csv(header() + "G1,user@uvt.ro,Smith,Jane,Conf.," + DEPT + "\n"), INST);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        User.ResearcherProfile profile = captor.getValue().getResearcherProfile();
        assertEquals("Jane", profile.getFirstName());
        assertEquals("Smith", profile.getLastName());
        assertEquals(Position.CONF_UNIV, profile.getPosition());
    }

    @Test
    void newUserScopusIdsPopulatedFromColumn() throws Exception {
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(csv(
                "groupName,email,lastName,firstName,position,departmentId,scopusIds\n"
                + "G1,user@uvt.ro,Doe,John,Prof.," + DEPT + ",11111;22222\n"), INST);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertEquals(List.of("11111", "22222"), captor.getValue().getResearcherProfile().getScopusId());
    }

    @Test
    void newUserScopusIdsNotSetWhenColumnAbsent() throws Exception {
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(
                csv(header() + "G1,user@uvt.ro,Doe,John,Prof.," + DEPT + "\n"), INST);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertTrue(captor.getValue().getResearcherProfile().getScopusId().isEmpty());
    }

    // --- existing user update ---

    @Test
    void existingUserProfileUpdatedNotCreated() throws Exception {
        User existing = new User();
        existing.setEmail("user@uvt.ro");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(existing));

        groupService.importGroupsFromCsv(
                csv(header() + "G1,user@uvt.ro,Doe,John,Prof.," + DEPT + "\n"), INST);

        verify(userService, never()).createUser(any());
        verify(userService).updateUser(eq("user@uvt.ro"), any(User.class));
    }

    @Test
    void existingUserResearcherRoleAdded() throws Exception {
        User existing = new User();
        existing.setEmail("user@uvt.ro");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(existing));

        groupService.importGroupsFromCsv(
                csv(header() + "G1,user@uvt.ro,Doe,John,Conf.," + DEPT + "\n"), INST);

        assertTrue(existing.getRoles().contains(UserRole.RESEARCHER));
    }

    // --- position parsing ---

    @ParameterizedTest
    @CsvSource({
            "Asist. Cerc. Dr., ASIST_C",
            "Asist. Dr.,       ASIST_UNIV",
            "Lect. Dr.,        LECT_UNIV",
            "Conf. Dr.,        CONF_UNIV",
            "Prof. Dr.,        PROF_UNIV",
            "CS III,           CS_III",
            "CS II,            CS_II",
            "CS I,             CS_I"
    })
    void parsePositionMapsKnownPrefixes(String positionField, String expectedName) throws Exception {
        Position expected = Position.valueOf(expectedName.trim());
        when(userService.getUserByEmail(any())).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(csv(header()
                + "G1,user@uvt.ro,Doe,John," + positionField.trim() + "," + DEPT + "\n"), INST);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertEquals(expected, captor.getValue().getResearcherProfile().getPosition());
    }

    @Test
    void parsePositionRejectsUnknownInsteadOfFallingBackToOther() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv(header()
                        + "G1,user@uvt.ro,Doe,John,Wizard," + DEPT + "\n"), INST));
        assertTrue(ex.getMessage().contains("unknown position"), ex.getMessage());
        verify(groupRepository, never()).saveAll(any());
    }

    // --- CSV validation ---

    @Test
    void importGroupsRejectsInvalidEmail() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv(header()
                        + "G1,invalid-email,Doe,John,Prof.," + DEPT + "\n"), INST));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsRejectsMissingRequiredColumnsInHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(
                        csv("groupName,email,lastName,firstName,position\nG1,john@uvt.ro,Doe,John,Prof.\n"), INST));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsRejectsRowWithTooFewColumns() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv(header() + "G1,user@uvt.ro,Doe\n"), INST));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsRejectsBlankRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv(header()
                        + "G1,user@uvt.ro,,John,Prof.," + DEPT + "\n"), INST));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsRejectsUnknownDepartmentId() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv(header()
                        + "G1,user@uvt.ro,Doe,John,Prof.,does-not-exist\n"), INST));
        assertTrue(ex.getMessage().contains("departmentId"), ex.getMessage());
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsSkipsBlankLinesBetweenRows() throws Exception {
        when(userService.getUserByEmail(any())).thenReturn(Optional.empty());
        groupService.importGroupsFromCsv(csv(header()
                + "\n"
                + "G1,user@uvt.ro,Doe,John,Prof.," + DEPT + "\n"), INST);
        verify(groupRepository).saveAll(any());
    }

    @Test
    void importGroupsRejectsEmptyFile() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv(""), INST));
    }

    @Test
    void importGroupsRejectsBlankHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv("   \n" + header()
                        + "G1,a@b.com,Doe,John,Prof.," + DEPT + "\n"), INST));
    }

    @Test
    void importGroupsRejectsBlankPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv(header()
                        + "G1,user@uvt.ro,Doe,John,," + DEPT + "\n"), INST));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsPersistsValidatedRows() throws Exception {
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userService.getUserByEmail("john@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(csv(
                "groupName,email,lastName,firstName,position,departmentId,scopusIds\n"
                + "G1,john@uvt.ro,Doe,John,Prof.," + DEPT + ",12345\n"), INST);

        ArgumentCaptor<Iterable<Group>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(groupRepository).saveAll(captor.capture());
        assertTrue(captor.getValue().iterator().hasNext());
    }

    // --- department affiliation ---

    @Test
    void importGroupsCreatesPrimaryDepartmentAffiliationWhenAbsent() throws Exception {
        when(userService.getUserByEmail(any())).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(
                csv(header() + "G1,jane@uvt.ro,Smith,Jane,Prof.," + DEPT + "\n"), INST);

        ArgumentCaptor<List<DepartmentAffiliation>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(departmentAffiliationRepository).saveAll(captor.capture());
        List<DepartmentAffiliation> saved = captor.getValue();
        assertEquals(1, saved.size());
        DepartmentAffiliation aff = saved.get(0);
        assertEquals("jane@uvt.ro", aff.getUserId());
        assertEquals(DEPT, aff.getDepartmentId());
        assertTrue(aff.isPrimary());
        assertNotNull(aff.getValidFrom());
        assertNull(aff.getValidTo());
    }

    @Test
    void importGroupsLeavesExistingPrimaryDepartmentAffiliationUnchanged() throws Exception {
        when(userService.getUserByEmail(any())).thenReturn(Optional.empty());
        DepartmentAffiliation existing = new DepartmentAffiliation();
        existing.setUserId("jane@uvt.ro");
        existing.setDepartmentId(DEPT);
        existing.setPrimary(true);
        when(departmentAffiliationRepository.findByUserIdInAndValidToIsNull(any()))
                .thenReturn(List.of(existing));

        groupService.importGroupsFromCsv(
                csv(header() + "G1,jane@uvt.ro,Smith,Jane,Prof.," + DEPT + "\n"), INST);

        verify(departmentAffiliationRepository, never()).saveAll(any());
    }

    // --- helpers ---

    private void stubNewUser() {
        when(userService.getUserByEmail(anyString())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private Group captureFirstSavedGroup() {
        ArgumentCaptor<Iterable<Group>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(groupRepository).saveAll(captor.capture());
        return captor.getValue().iterator().next();
    }

    private static String header() {
        return "groupName,email,lastName,firstName,position,departmentId\n";
    }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "groups.csv", "text/csv",
                body.getBytes(StandardCharsets.UTF_8));
    }
}
