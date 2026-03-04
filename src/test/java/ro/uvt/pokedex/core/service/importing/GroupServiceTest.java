package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private GroupRepository groupRepository;
    @Mock
    private InstitutionRepository institutionRepository;
    @Mock
    private ResearcherService researcherService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserService userService;

    @InjectMocks
    private GroupService groupService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(groupService, "defaultPassword", "2025");
        ReflectionTestUtils.setField(groupService, "requiredColumnCount", 5);

        Institution uvt = new Institution();
        uvt.setName("UVT");
        when(institutionRepository.findByNameIgnoreCase("UVT")).thenReturn(List.of(uvt));
    }

    @Test
    void importGroupsRejectsInvalidEmail() {
        MockMultipartFile file = csv(
                "groupName,email,lastName,firstName,position\n" +
                        "G1,invalid-email,Doe,John,Prof.\n"
        );

        assertThrows(IllegalArgumentException.class, () -> groupService.importGroupsFromCsv(file));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsRejectsMissingRequiredColumns() {
        MockMultipartFile file = csv(
                "groupName,email,lastName,firstName\n" +
                        "G1,john@uvt.ro,Doe,John\n"
        );

        assertThrows(IllegalArgumentException.class, () -> groupService.importGroupsFromCsv(file));
        verify(groupRepository, never()).saveAll(any());
    }

    @Test
    void importGroupsPersistsValidatedRows() throws Exception {
        MockMultipartFile file = csv(
                "groupName,email,lastName,firstName,position,scopusIds\n" +
                        "G1,john@uvt.ro,Doe,John,Prof.,12345\n"
        );

        Researcher savedResearcher = new Researcher();
        savedResearcher.setId("r1");
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(researcherService.saveResearcher(any())).thenReturn(savedResearcher);
        when(userService.getUserByEmail("john@uvt.ro")).thenReturn(Optional.empty());
        when(userService.createUser(any(User.class))).thenReturn(Optional.of(new User()));

        groupService.importGroupsFromCsv(file);

        ArgumentCaptor<Iterable<Group>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(groupRepository).saveAll(captor.capture());
        assertTrue(captor.getValue().iterator().hasNext());
    }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile(
                "file",
                "groups.csv",
                "text/csv",
                body.getBytes(StandardCharsets.UTF_8)
        );
    }
}
