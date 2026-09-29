package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserScopusTasksViewModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserScopusTaskFacadeTest {

    @Mock
    private UserService userService;
    @Mock
    private ScopusPublicationUpdateRepository scopusPublicationUpdateRepository;
    @Mock
    private ScopusCitationUpdateRepository scopusCitationUpdateRepository;
    @Mock
    private org.springframework.beans.factory.ObjectProvider<ro.uvt.pokedex.core.service.scopus.ScopusUpdateScheduler>
            scopusUpdateScheduler;

    @InjectMocks
    private UserScopusTaskFacade facade;

    @Test
    void createPublicationTaskSetsInitiatorAndPendingStatus() {
        ScopusPublicationUpdate draft = new ScopusPublicationUpdate();
        draft.setScopusId("57000000001");
        owner("57000000001");
        when(scopusPublicationUpdateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScopusPublicationUpdate saved = facade.createPublicationTask("user@uvt.ro", draft);

        assertEquals("user@uvt.ro", saved.getInitiator());
        assertEquals(Status.PENDING, saved.getStatus());
        assertNotNull(saved.getInitiatedDate());
        assertEquals(0, saved.getAttemptCount());
        assertEquals(3, saved.getMaxAttempts());
        // Creating a task kicks the (optional) scheduler for an immediate poll.
        org.mockito.Mockito.verify(scopusUpdateScheduler).ifAvailable(any());
    }

    @Test
    void buildTasksViewLoadsUserAndTaskLists() {
        User user = new User();
        user.setEmail("user@uvt.ro");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(scopusPublicationUpdateRepository.findByInitiator("user@uvt.ro")).thenReturn(List.of(new ScopusPublicationUpdate()));
        when(scopusCitationUpdateRepository.findByInitiator("user@uvt.ro")).thenReturn(List.of(new ScopusCitationsUpdate()));

        UserScopusTasksViewModel view = facade.buildTasksView("user@uvt.ro", "researcher-1");
        assertEquals(user, view.user());
        assertEquals(1, view.tasks().size());
        assertEquals(1, view.citationsTasks().size());
    }

    @Test
    void createPublicationTaskKeepsPositiveMaxAttempts() {
        ScopusPublicationUpdate draft = new ScopusPublicationUpdate();
        draft.setScopusId("57000000001");
        owner("57000000001");
        draft.setMaxAttempts(5);
        draft.setAttemptCount(7);
        draft.setNextAttemptAt("soon");
        draft.setLastErrorCode("E");
        draft.setLastErrorMessage("M");
        when(scopusPublicationUpdateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScopusPublicationUpdate saved = facade.createPublicationTask("user@uvt.ro", draft);
        assertEquals(5, saved.getMaxAttempts());
        assertEquals(0, saved.getAttemptCount());
        assertNull(saved.getNextAttemptAt());
        assertNull(saved.getLastErrorCode());
        assertNull(saved.getLastErrorMessage());
    }

    @Test
    void createCitationTaskSetsDefaults() {
        ScopusCitationsUpdate draft = new ScopusCitationsUpdate();
        draft.setScopusId("57000000001");
        owner("57000000001");
        when(scopusCitationUpdateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScopusCitationsUpdate saved = facade.createCitationTask("user@uvt.ro", draft);
        assertEquals("user@uvt.ro", saved.getInitiator());
        assertEquals(Status.PENDING, saved.getStatus());
        assertNotNull(saved.getInitiatedDate());
        assertEquals(0, saved.getAttemptCount());
        assertEquals(3, saved.getMaxAttempts());
        assertNull(saved.getNextAttemptAt());
        assertNull(saved.getLastErrorCode());
        assertNull(saved.getLastErrorMessage());
    }

    @Test
    void createCitationTaskKeepsPositiveMaxAttemptsAndResetsFields() {
        ScopusCitationsUpdate draft = new ScopusCitationsUpdate();
        draft.setScopusId("57000000001");
        owner("57000000001");
        draft.setMaxAttempts(4);
        draft.setAttemptCount(2);
        draft.setNextAttemptAt("soon");
        draft.setLastErrorCode("OLD");
        draft.setLastErrorMessage("old");
        when(scopusCitationUpdateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScopusCitationsUpdate saved = facade.createCitationTask("user@uvt.ro", draft);
        assertEquals(4, saved.getMaxAttempts());
        assertEquals(0, saved.getAttemptCount());
        assertNull(saved.getNextAttemptAt());
        assertNull(saved.getLastErrorCode());
        assertNull(saved.getLastErrorMessage());
    }

    @Test
    void aSyncForAScopusIdOfAnotherPersonIsRefused() {
        // H128: the id comes from the request; only one's own may be synced
        owner("57000000001");
        ScopusPublicationUpdate publications = new ScopusPublicationUpdate();
        publications.setScopusId("57999999999");
        ScopusCitationsUpdate citations = new ScopusCitationsUpdate();
        citations.setScopusId("57999999999");

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> facade.createPublicationTask("user@uvt.ro", publications));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> facade.createCitationTask("user@uvt.ro", citations));
        org.mockito.Mockito.verifyNoInteractions(scopusPublicationUpdateRepository, scopusCitationUpdateRepository);
    }

    @Test
    void aPlatformAdminStartsTheSyncOfAnybody() {
        User admin = new User();
        admin.setEmail("admin@uvt.ro");
        admin.setRoles(java.util.Set.of(ro.uvt.pokedex.core.model.user.UserRole.PLATFORM_ADMIN));
        when(userService.getUserByEmail("admin@uvt.ro")).thenReturn(Optional.of(admin));
        when(scopusPublicationUpdateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ScopusPublicationUpdate draft = new ScopusPublicationUpdate();
        draft.setScopusId("57999999999");

        assertEquals("admin@uvt.ro", facade.createPublicationTask("admin@uvt.ro", draft).getInitiator());
    }

    private void owner(String scopusId) {
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setRoles(java.util.Set.of(ro.uvt.pokedex.core.model.user.UserRole.RESEARCHER));
        ro.uvt.pokedex.core.model.user.User.ResearcherProfile profile = new ro.uvt.pokedex.core.model.user.User.ResearcherProfile();
        profile.setScopusId(new java.util.ArrayList<>(List.of(scopusId)));
        user.setResearcherProfile(profile);
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
    }
}
