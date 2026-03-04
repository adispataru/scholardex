package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.application.model.UserScopusTasksViewModel;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserScopusTaskFacade {
    private final ResearcherService researcherService;
    private final ScopusPublicationUpdateRepository scopusPublicationUpdateRepository;
    private final ScopusCitationUpdateRepository scopusCitationUpdateRepository;

    public UserScopusTasksViewModel buildTasksView(String userEmail, String researcherId) {
        Researcher researcher = researcherService.findResearcherById(researcherId).orElse(null);
        List<ScopusPublicationUpdate> tasks = scopusPublicationUpdateRepository.findByInitiator(userEmail);
        List<ScopusCitationsUpdate> citationsTasks = scopusCitationUpdateRepository.findByInitiator(userEmail);
        return new UserScopusTasksViewModel(researcher, tasks, citationsTasks);
    }

    public ScopusPublicationUpdate createPublicationTask(String userEmail, ScopusPublicationUpdate draft) {
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
        return scopusPublicationUpdateRepository.save(draft);
    }

    public ScopusCitationsUpdate createCitationTask(String userEmail, ScopusCitationsUpdate draft) {
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
        return scopusCitationUpdateRepository.save(draft);
    }
}
