package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserScopusTasksViewModel;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserScopusTaskFacade {
    private final UserService userService;
    private final ScopusPublicationUpdateRepository scopusPublicationUpdateRepository;
    private final ScopusCitationUpdateRepository scopusCitationUpdateRepository;
    // ObjectProvider because the scheduler bean is conditional on core.scopus.scheduler.enabled
    // (disabled in test runs); the kick is best-effort — the scheduled poll picks the task up anyway.
    private final org.springframework.beans.factory.ObjectProvider<ro.uvt.pokedex.core.service.scopus.ScopusUpdateScheduler>
            scopusUpdateScheduler;

    public UserScopusTasksViewModel buildTasksView(String userEmail, String researcherId) {
        // researcherId is kept for API compatibility; after migration the researcher
        // is resolved via userEmail (which is now the canonical researcher id).
        String lookupId = (userEmail != null && !userEmail.isBlank()) ? userEmail : researcherId;
        User user = userService.getUserByEmail(lookupId).orElse(null);
        List<ScopusPublicationUpdate> tasks = scopusPublicationUpdateRepository.findByInitiator(userEmail);
        List<ScopusCitationsUpdate> citationsTasks = scopusCitationUpdateRepository.findByInitiator(userEmail);
        return new UserScopusTasksViewModel(user, tasks, citationsTasks);
    }

    public ScopusPublicationUpdate createPublicationTask(String userEmail, ScopusPublicationUpdate draft) {
        initializeDraft(draft, userEmail);
        ScopusPublicationUpdate saved = scopusPublicationUpdateRepository.save(draft);
        // Kick the scheduler so this runs within seconds instead of waiting for the next poll.
        scopusUpdateScheduler.ifAvailable(
                ro.uvt.pokedex.core.service.scopus.ScopusUpdateScheduler::triggerImmediatePoll);
        return saved;
    }

    public ScopusCitationsUpdate createCitationTask(String userEmail, ScopusCitationsUpdate draft) {
        initializeDraft(draft, userEmail);
        ScopusCitationsUpdate saved = scopusCitationUpdateRepository.save(draft);
        scopusUpdateScheduler.ifAvailable(
                ro.uvt.pokedex.core.service.scopus.ScopusUpdateScheduler::triggerImmediatePoll);
        return saved;
    }

    private void initializeDraft(ScopusPublicationUpdate draft, String userEmail) {
        draft.setInitiator(userEmail);
        draft.setStatus(Status.PENDING);
        draft.setInitiatedDate(java.time.Instant.now().toString());
        if (draft.getMaxAttempts() <= 0) {
            draft.setMaxAttempts(3);
        }
        draft.setAttemptCount(0);
        draft.setNextAttemptAt(null);
        draft.setLastErrorCode(null);
        draft.setLastErrorMessage(null);
    }

    private void initializeDraft(ScopusCitationsUpdate draft, String userEmail) {
        draft.setInitiator(userEmail);
        draft.setStatus(Status.PENDING);
        draft.setInitiatedDate(java.time.Instant.now().toString());
        if (draft.getMaxAttempts() <= 0) {
            draft.setMaxAttempts(3);
        }
        draft.setAttemptCount(0);
        draft.setNextAttemptAt(null);
        draft.setLastErrorCode(null);
        draft.setLastErrorMessage(null);
    }
}
