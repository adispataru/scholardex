package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ForumReconcileSchedulerTest {

    @Mock private ForumReconcileService forumReconcileService;

    @Test
    void scheduledRunInvokesReconcileWhenEnabled() {
        new ForumReconcileScheduler(forumReconcileService, true).runScheduledReconcile();
        verify(forumReconcileService).reconcile("scheduled-nightly");
    }

    @Test
    void scheduledRunSkipsWhenDisabled() {
        new ForumReconcileScheduler(forumReconcileService, false).runScheduledReconcile();
        verify(forumReconcileService, never()).reconcile(any());
    }
}
