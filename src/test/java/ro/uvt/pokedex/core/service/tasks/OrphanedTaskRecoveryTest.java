package ro.uvt.pokedex.core.service.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.tasks.OpenAlexAuthorUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.tasks.OpenAlexAuthorUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrphanedTaskRecoveryTest {

    @Mock private OpenAlexAuthorUpdateRepository openAlexTasks;
    @Mock private ScopusPublicationUpdateRepository scopusPublicationTasks;
    @Mock private ScopusCitationUpdateRepository scopusCitationTasks;

    private OrphanedTaskRecovery recovery() {
        return new OrphanedTaskRecovery(openAlexTasks, scopusPublicationTasks, scopusCitationTasks, 3);
    }

    @Test
    void anInterruptedTaskWithAttemptsLeftGoesBackToPending() {
        OpenAlexAuthorUpdate task = new OpenAlexAuthorUpdate();
        task.setStatus(Status.IN_PROGRESS);
        task.setAttemptCount(1);
        task.setMaxAttempts(3);
        task.setNextAttemptAt("2026-09-01T12:00:00Z");
        task.setMessage("Starting OpenAlex author sync");
        when(openAlexTasks.findByStatusOrderByInitiatedDate(Status.IN_PROGRESS)).thenReturn(List.of(task));

        assertEquals(1, recovery().recoverAll());

        assertEquals(Status.PENDING, task.getStatus());
        assertNull(task.getNextAttemptAt(), "must be picked up on the very next poll");
        assertEquals(1, task.getAttemptCount(), "the interrupted attempt stays counted");
        assertEquals(OrphanedTaskRecovery.ERROR_CODE, task.getLastErrorCode());
        assertTrue(task.getMessage().contains("Re-queued"));
        verify(openAlexTasks).save(task);
    }

    @Test
    void aTaskThatUsedAllItsAttemptsIsClosedAsFailedInsteadOfLoopingForever() {
        ScopusCitationsUpdate task = new ScopusCitationsUpdate();
        task.setStatus(Status.IN_PROGRESS);
        task.setAttemptCount(3);
        task.setMaxAttempts(3);
        when(scopusCitationTasks.findByStatusOrderByInitiatedDate(Status.IN_PROGRESS)).thenReturn(List.of(task));

        assertEquals(1, recovery().recoverAll());

        assertEquals(Status.FAILED, task.getStatus());
        assertTrue(task.getLastErrorMessage().contains("no attempts left"));
        verify(scopusCitationTasks).save(task);
    }

    @Test
    void aLegacyTaskWithoutMaxAttemptsUsesTheDefault() {
        OpenAlexAuthorUpdate task = new OpenAlexAuthorUpdate();
        task.setStatus(Status.IN_PROGRESS);
        task.setAttemptCount(1);
        task.setMaxAttempts(0);
        when(openAlexTasks.findByStatusOrderByInitiatedDate(Status.IN_PROGRESS)).thenReturn(List.of(task));

        recovery().recoverAll();

        assertEquals(Status.PENDING, task.getStatus());
    }

    @Test
    void nothingIsWrittenWhenNoTaskIsInProgress() {
        assertEquals(0, recovery().recoverAll());
        verify(openAlexTasks, never()).save(any());
        verify(scopusPublicationTasks, never()).save(any());
        verify(scopusCitationTasks, never()).save(any());
    }

    @Test
    void aRepositoryFailureNeverBlocksStartup() {
        when(openAlexTasks.findByStatusOrderByInitiatedDate(Status.IN_PROGRESS))
                .thenThrow(new IllegalStateException("mongo down"));
        recovery().recoverOnStartup(); // must not throw
    }
}
