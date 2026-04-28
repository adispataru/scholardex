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
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.UserService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock private GroupRepository groupRepository;
    @Mock private InstitutionRepository institutionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserService userService;

    private GroupService groupService;
    private Institution uvt;

    @BeforeEach
    void setUp() {
        groupService = new GroupService(
                groupRepository, institutionRepository, passwordEncoder, userService, "2025", 5
        );
        uvt = new Institution();
        uvt.setName("UVT");
        when(institutionRepository.findByNameIgnoreCase("UVT")).thenReturn(List.of(uvt));
    }

    // --- early exit ---

    @Test
    void noUvtInstitution_skipsImportEntirely() throws Exception {
        when(institutionRepository.findByNameIgnoreCase("UVT")).thenReturn(List.of());
        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,a@b.com,Doe,John,Prof.\n"));
        verify(groupRepository, never()).saveAll(any());
    }

    // --- group field propagation ---

    @Test
    void importGroups_setsGroupNameDescriptionAndInstitution() throws Exception {
        stubNewUser();
        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nResearchLab,user@uvt.ro,Doe,John,Prof.\n"));

        Group saved = captureFirstSavedGroup();
        assertEquals("ResearchLab", saved.getName());
        assertEquals("Imported from CSV", saved.getDescription());
        assertEquals(uvt, saved.getInstitution());
    }

    @Test
    void importGroups_memberIdAddedToGroup() throws Exception {
        stubNewUser();
        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Doe,John,Prof.\n"));
        assertTrue(captureFirstSavedGroup().getMemberIds().contains("user@uvt.ro"));
    }

    @Test
    void importGroups_deduplicatesMemberIdsForSameEmail() throws Exception {
        when(userService.getUserByEmail("user@uvt.ro"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new User()));
        groupService.importGroupsFromCsv(csv(
                "groupName,email,lastName,firstName,position\n" +
                "G1,user@uvt.ro,Doe,John,Prof.\n" +
                "G1,user@uvt.ro,Doe,John,Prof.\n"
        ));
        assertEquals(1, captureFirstSavedGroup().getMemberIds().stream()
                .filter("user@uvt.ro"::equals).count());
    }

    @Test
    void importGroups_accumulatesDistinctMemberIds() throws Exception {
        when(userService.getUserByEmail(any())).thenReturn(Optional.empty());
        groupService.importGroupsFromCsv(csv(
                "groupName,email,lastName,firstName,position\n" +
                "G1,alice@uvt.ro,Alice,A,Prof.\n" +
                "G1,bob@uvt.ro,Bob,B,Prof.\n"
        ));
        List<String> ids = captureFirstSavedGroup().getMemberIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("alice@uvt.ro"));
        assertTrue(ids.contains("bob@uvt.ro"));
    }

    // --- new user creation ---

    @Test
    void newUser_createdWithEncodedPasswordAndResearcherRole() throws Exception {
        when(passwordEncoder.encode("2025")).thenReturn("hashed");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Doe,John,Conf.\n"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        User created = captor.getValue();
        assertEquals("user@uvt.ro", created.getEmail());
        assertEquals("hashed", created.getPassword());
        assertTrue(created.getRoles().contains(UserRole.RESEARCHER));
    }

    @Test
    void newUser_researcherProfileContainsFirstNameLastNameAndPosition() throws Exception {
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Smith,Jane,Conf.\n"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        User.ResearcherProfile profile = captor.getValue().getResearcherProfile();
        assertEquals("Jane", profile.getFirstName());
        assertEquals("Smith", profile.getLastName());
        assertEquals(Position.CONF_UNIV, profile.getPosition());
    }

    @Test
    void newUser_scopusIdsPopulatedFromColumn() throws Exception {
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position,scopusIds\nG1,user@uvt.ro,Doe,John,Prof.,11111;22222\n"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        List<String> scopusIds = captor.getValue().getResearcherProfile().getScopusId();
        assertNotNull(scopusIds);
        assertEquals(List.of("11111", "22222"), scopusIds);
    }

    @Test
    void newUser_scopusIdsNotSetWhenColumnAbsent() throws Exception {
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Doe,John,Prof.\n"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertTrue(captor.getValue().getResearcherProfile().getScopusId().isEmpty());
    }

    @Test
    void newUser_blankScopusEntriesFilteredOut() throws Exception {
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position,scopusIds\nG1,user@uvt.ro,Doe,John,Prof.,  ;  ;\n"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertTrue(captor.getValue().getResearcherProfile().getScopusId().isEmpty());
    }

    // --- existing user update ---

    @Test
    void existingUser_profileUpdatedNotCreated() throws Exception {
        User existing = new User();
        existing.setEmail("user@uvt.ro");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(existing));

        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Doe,John,Prof.\n"));

        verify(userService, never()).createUser(any());
        verify(userService).updateUser(eq("user@uvt.ro"), any(User.class));
    }

    @Test
    void existingUser_researcherRoleAdded() throws Exception {
        User existing = new User();
        existing.setEmail("user@uvt.ro");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(existing));

        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Doe,John,Conf.\n"));

        assertTrue(existing.getRoles().contains(UserRole.RESEARCHER));
    }

    @Test
    void existingUser_researcherProfileBuiltAndPassed() throws Exception {
        User existing = new User();
        existing.setEmail("user@uvt.ro");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(existing));

        groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Smith,Jane,Lect.\n"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).updateUser(eq("user@uvt.ro"), captor.capture());
        User.ResearcherProfile profile = captor.getValue().getResearcherProfile();
        assertNotNull(profile);
        assertEquals("Jane", profile.getFirstName());
        assertEquals("Smith", profile.getLastName());
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
            "CS I,             CS_I",
            "unknown,          OTHER"
    })
    void parsePosition_mapsCorrectly(String positionField, String expectedName) throws Exception {
        Position expected = Position.valueOf(expectedName.trim());
        when(userService.getUserByEmail(any())).thenReturn(Optional.empty());

        groupService.importGroupsFromCsv(csv(
                "groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Doe,John," + positionField.trim() + "\n"
        ));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertEquals(expected, captor.getValue().getResearcherProfile().getPosition());
    }

    // --- CSV validation ---

    @Test
    void importGroupsRejectsInvalidEmail() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,invalid-email,Doe,John,Prof.\n")));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsRejectsMissingRequiredColumnsInHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName\nG1,john@uvt.ro,Doe,John\n")));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsRejectsRowWithTooFewColumns() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Doe\n")));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsRejectsBlankRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,,John,Prof.\n")));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroups_skipsBlankLinesBetweenRows() throws Exception {
        when(userService.getUserByEmail(any())).thenReturn(Optional.empty());
        groupService.importGroupsFromCsv(csv(
                "groupName,email,lastName,firstName,position\n" +
                "\n" +
                "G1,user@uvt.ro,Doe,John,Prof.\n"
        ));
        verify(groupRepository).saveAll(any());
    }

    @Test
    void importGroups_rejectsEmptyFile() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv("")));
    }

    @Test
    void importGroups_rejectsBlankHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv("   \ngroupName,email,lastName,firstName,position\nG1,a@b.com,Doe,John,Prof.\n")));
    }

    @Test
    void importGroupsRejectsBlankPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> groupService.importGroupsFromCsv(csv("groupName,email,lastName,firstName,position\nG1,user@uvt.ro,Doe,John,\n")));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsPersistsValidatedRows() throws Exception {
        MockMultipartFile file = csv("groupName,email,lastName,firstName,position,scopusIds\nG1,john@uvt.ro,Doe,John,Prof.,12345\n");
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userService.getUserByEmail("john@uvt.ro")).thenReturn(Optional.empty());
        when(userService.createUser(any(User.class))).thenReturn(Optional.of(new User()));

        groupService.importGroupsFromCsv(file);

        ArgumentCaptor<Iterable<Group>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(groupRepository).saveAll(captor.capture());
        assertTrue(captor.getValue().iterator().hasNext());
    }

    // --- helpers ---

    private void stubNewUser() {
        when(userService.getUserByEmail(any())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private Group captureFirstSavedGroup() {
        ArgumentCaptor<Iterable<Group>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(groupRepository).saveAll(captor.capture());
        return captor.getValue().iterator().next();
    }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "groups.csv", "text/csv",
                body.getBytes(StandardCharsets.UTF_8));
    }
}
