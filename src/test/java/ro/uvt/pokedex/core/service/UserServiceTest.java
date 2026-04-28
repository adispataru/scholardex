package ro.uvt.pokedex.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.UserRepository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void createUserObjectSavesWhenEmailIsNew() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User user = user("new@uvt.ro");
        when(userRepository.findById("new@uvt.ro")).thenReturn(Optional.empty());
        when(userRepository.save(user)).thenReturn(user);

        Optional<User> created = service.createUser(user);

        assertTrue(created.isPresent());
        assertSame(user, created.get());
        verify(userRepository).save(user);
    }

    @Test
    void createUserObjectReturnsEmptyWhenEmailExists() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User user = user("existing@uvt.ro");
        when(userRepository.findById("existing@uvt.ro")).thenReturn(Optional.of(user));

        assertTrue(service.createUser(user).isEmpty());
        verify(userRepository, never()).save(any());
    }

    @Test
    void getAndDeleteOperationsDelegateToRepository() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User user = user("researcher@uvt.ro");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(user));

        assertEquals(List.of(user), service.getAllUsers());
        assertEquals(Optional.of(user), service.getUserByEmail("researcher@uvt.ro"));

        service.deleteUser("researcher@uvt.ro");

        verify(userRepository).deleteById("researcher@uvt.ro");
    }

    @Test
    void updateUserSavesReplacementOnlyWhenUserExists() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User existing = user("old@uvt.ro");
        User replacement = user("new@uvt.ro");
        when(userRepository.findById("old@uvt.ro")).thenReturn(Optional.of(existing));
        when(userRepository.save(replacement)).thenReturn(replacement);

        Optional<User> updated = service.updateUser("old@uvt.ro", replacement);

        assertEquals(Optional.of(replacement), updated);
        verify(userRepository).save(replacement);
    }

    @Test
    void lockUserMarksUserLockedAndSavesIt() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User user = user("researcher@uvt.ro");
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(user));

        service.lockUser("researcher@uvt.ro");

        assertTrue(user.isLocked());
        verify(userRepository).save(user);
    }

    @Test
    void updateUserRolesReplacesRemovedRolesAndAddsNewRoles() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User user = user("researcher@uvt.ro");
        user.setRoles(new HashSet<>(Set.of(UserRole.RESEARCHER, UserRole.SUPERVISOR)));
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(user));

        service.updateUserRoles("researcher@uvt.ro", List.of("PLATFORM_ADMIN", "RESEARCHER"));

        assertEquals(Set.of(UserRole.PLATFORM_ADMIN, UserRole.RESEARCHER), user.getRoles());
        verify(userRepository).save(user);
    }

    @Test
    void updateUserRolesIgnoresNullRoleList() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User user = user("researcher@uvt.ro");
        user.setRoles(Set.of(UserRole.RESEARCHER));
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(user));

        service.updateUserRoles("researcher@uvt.ro", null);

        assertEquals(Set.of(UserRole.RESEARCHER), user.getRoles());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUserFromFieldsEncodesPasswordAndPersistsRoles() {
        UserService service = new UserService(userRepository, passwordEncoder);
        when(userRepository.findById("new@uvt.ro")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret")).thenReturn("encoded-secret");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<User> created = service.createUser("new@uvt.ro", "secret", List.of("RESEARCHER", "SUPERVISOR"));

        assertTrue(created.isPresent());
        assertEquals("new@uvt.ro", created.get().getEmail());
        assertEquals("encoded-secret", created.get().getPassword());
        assertEquals(Set.of(UserRole.RESEARCHER, UserRole.SUPERVISOR), created.get().getRoles());
    }

    @Test
    void createUserFromFieldsReturnsEmptyWhenEmailExists() {
        UserService service = new UserService(userRepository, passwordEncoder);
        when(userRepository.findById("existing@uvt.ro")).thenReturn(Optional.of(user("existing@uvt.ro")));

        assertTrue(service.createUser("existing@uvt.ro", "secret", List.of("RESEARCHER")).isEmpty());
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void roleHelpersValidateAndParseKnownRoleNames() {
        UserService service = new UserService(userRepository, passwordEncoder);

        assertFalse(service.areValidRoleNames(null));
        assertFalse(service.areValidRoleNames(List.of()));
        assertFalse(service.areValidRoleNames(List.of("UNKNOWN")));
        assertTrue(service.areValidRoleNames(List.of("PLATFORM_ADMIN", "RESEARCHER")));
        assertEquals(Set.of(UserRole.PLATFORM_ADMIN, UserRole.RESEARCHER),
                service.parseRoles(List.of("PLATFORM_ADMIN", "RESEARCHER")));
        assertEquals(UserRole.SUPERVISOR, service.parseRoleOrThrow("SUPERVISOR"));
        assertThrows(IllegalArgumentException.class, () -> service.parseRoleOrThrow("UNKNOWN"));
    }

    @Test
    void userExistsReflectsRepositoryLookup() {
        UserService service = new UserService(userRepository, passwordEncoder);
        when(userRepository.findById("present@uvt.ro")).thenReturn(Optional.of(user("present@uvt.ro")));
        when(userRepository.findById("missing@uvt.ro")).thenReturn(Optional.empty());

        assertTrue(service.userExists("present@uvt.ro"));
        assertFalse(service.userExists("missing@uvt.ro"));
    }

    @Test
    void researcherProfileOperationsApplyAndClearProfileFields() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User user = user("researcher@uvt.ro");
        User.ResearcherProfile profile = profile("Ada", "Lovelace");
        profile.setScholarId("scholar-1");
        profile.setScopusId(List.of("scopus-1"));
        profile.setWosId(List.of("wos-1"));
        profile.setPrimaryScholardexAuthorId("author-1");
        profile.setCurrentAffiliationIds(List.of("current-1"));
        profile.setPastAffiliationIds(List.of("past-1"));
        profile.setAffiliationsConfirmedAt(Instant.parse("2026-04-28T10:15:30Z"));
        profile.setPosition(Position.LECT_UNIV);
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        User saved = service.saveResearcherProfile("researcher@uvt.ro", profile);

        assertSame(user, saved);
        assertEquals("Ada", user.getResearcherProfile().getFirstName());
        assertEquals("Lovelace", user.getResearcherProfile().getLastName());
        assertEquals("scholar-1", user.getResearcherProfile().getScholarId());
        assertEquals(List.of("scopus-1"), user.getResearcherProfile().getScopusId());
        assertEquals(List.of("wos-1"), user.getResearcherProfile().getWosId());
        assertEquals("author-1", user.getResearcherProfile().getPrimaryScholardexAuthorId());
        assertEquals(List.of("current-1"), user.getResearcherProfile().getCurrentAffiliationIds());
        assertEquals(List.of("past-1"), user.getResearcherProfile().getPastAffiliationIds());
        assertEquals(Instant.parse("2026-04-28T10:15:30Z"), user.getResearcherProfile().getAffiliationsConfirmedAt());
        assertEquals(Position.LECT_UNIV, user.getResearcherProfile().getPosition());

        service.deleteResearcherProfile("researcher@uvt.ro");

        assertNull(user.getResearcherProfile());
    }

    @Test
    void saveResearcherProfilePreservesExistingAffiliationsWhenIncomingListsAreEmpty() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User user = user("researcher@uvt.ro");
        User.ResearcherProfile existing = profile("Old", "Name");
        existing.setCurrentAffiliationIds(List.of("current-existing"));
        existing.setPastAffiliationIds(List.of("past-existing"));
        user.setResearcherProfile(existing);
        User.ResearcherProfile incoming = profile("New", "Name");
        incoming.setCurrentAffiliationIds(List.of());
        incoming.setPastAffiliationIds(List.of());
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        User updated = service.updateResearcherProfile("researcher@uvt.ro", incoming);

        assertSame(user, updated);
        assertEquals("New", user.getResearcherProfile().getFirstName());
        assertEquals(List.of("current-existing"), user.getResearcherProfile().getCurrentAffiliationIds());
        assertEquals(List.of("past-existing"), user.getResearcherProfile().getPastAffiliationIds());
    }

    @Test
    void saveResearcherProfileInitializesEmptyAffiliationListsWhenBothSidesAreMissing() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User user = user("researcher@uvt.ro");
        User.ResearcherProfile existing = profile("Old", "Name");
        existing.setCurrentAffiliationIds(null);
        existing.setPastAffiliationIds(null);
        user.setResearcherProfile(existing);
        User.ResearcherProfile incoming = profile("New", "Name");
        incoming.setCurrentAffiliationIds(null);
        incoming.setPastAffiliationIds(null);
        when(userRepository.findById("researcher@uvt.ro")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        service.saveResearcherProfile("researcher@uvt.ro", incoming);

        assertTrue(user.getResearcherProfile().getCurrentAffiliationIds().isEmpty());
        assertTrue(user.getResearcherProfile().getPastAffiliationIds().isEmpty());
    }

    @Test
    void saveResearcherProfileThrowsWhenUserIsMissing() {
        UserService service = new UserService(userRepository, passwordEncoder);
        when(userRepository.findById("missing@uvt.ro")).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.saveResearcherProfile("missing@uvt.ro", profile("Ada", "Lovelace"))
        );

        assertTrue(exception.getMessage().contains("missing@uvt.ro"));
    }

    @Test
    void findAndMatchResearchersUseNormalizedFirstAndLastName() {
        UserService service = new UserService(userRepository, passwordEncoder);
        User matching = user("ada@uvt.ro");
        matching.setResearcherProfile(profile("Ada", "Lovelace"));
        User nonMatching = user("grace@uvt.ro");
        nonMatching.setResearcherProfile(profile("Grace", "Hopper"));
        when(userRepository.findAllByResearcherProfileIsNotNull()).thenReturn(List.of(matching, nonMatching));

        assertEquals(List.of(matching, nonMatching), service.findUsersWithResearcherProfile());
        assertEquals(Optional.of(matching), service.matchAuthorToUser("Ada Byron Lovelace"));
        assertEquals(Optional.empty(), service.matchAuthorToUser(""));
        assertEquals(Optional.empty(), service.matchAuthorToUser("Ada Hopper"));
    }

    @Test
    void deleteResearcherProfileDoesNothingWhenUserIsMissing() {
        UserService service = new UserService(userRepository, passwordEncoder);
        when(userRepository.findById("missing@uvt.ro")).thenReturn(Optional.empty());

        service.deleteResearcherProfile("missing@uvt.ro");

        verify(userRepository, never()).save(any());
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private User.ResearcherProfile profile(String firstName, String lastName) {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName(firstName);
        profile.setLastName(lastName);
        return profile;
    }
}
