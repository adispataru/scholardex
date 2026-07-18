package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.tasks.OpenAlexAuthorUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.tasks.OpenAlexAuthorUpdateRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * H66B Phase 4a — create + list OpenAlex author-sync tasks. Mirrors {@link UserScopusTaskFacade}.
 */
@Service
@RequiredArgsConstructor
public class UserOpenAlexTaskFacade {

    private final OpenAlexAuthorUpdateRepository openAlexAuthorUpdateRepository;
    /**
     * ObjectProvider because the scheduler is {@code @ConditionalOnProperty} — a hard constructor
     * dependency would fail every context that disables it (same lesson as the Scopus facade).
     */
    private final org.springframework.beans.factory.ObjectProvider<ro.uvt.pokedex.core.service.openalex.OpenAlexUpdateScheduler>
            openAlexUpdateScheduler;

    public OpenAlexAuthorUpdate createAuthorTask(String userEmail, OpenAlexAuthorUpdate draft) {
        draft.setInitiator(userEmail);
        draft.setStatus(Status.PENDING);
        draft.setInitiatedDate(LocalDate.now().toString());
        if (draft.getMaxAttempts() <= 0) {
            draft.setMaxAttempts(3);
        }
        draft.setAttemptCount(0);
        draft.setNextAttemptAt(null);
        draft.setLastErrorCode(null);
        draft.setLastErrorMessage(null);
        OpenAlexAuthorUpdate saved = openAlexAuthorUpdateRepository.save(draft);
        // Best-effort immediate kick: the sync starts within seconds instead of the next poll tick.
        openAlexUpdateScheduler.ifAvailable(
                ro.uvt.pokedex.core.service.openalex.OpenAlexUpdateScheduler::triggerImmediatePoll);
        return saved;
    }

    public List<OpenAlexAuthorUpdate> findTasksForUser(String userEmail) {
        return openAlexAuthorUpdateRepository.findByInitiator(userEmail);
    }
}
