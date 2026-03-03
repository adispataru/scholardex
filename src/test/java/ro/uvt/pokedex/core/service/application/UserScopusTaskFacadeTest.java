package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.ResearcherService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserScopusTaskFacadeTest {

    @Mock
    private ResearcherService researcherService;
    @Mock
    private ScopusPublicationUpdateRepository scopusPublicationUpdateRepository;
    @Mock
    private ScopusCitationUpdateRepository scopusCitationUpdateRepository;

    @InjectMocks
    private UserScopusTaskFacade facade;

    @Test
    void createPublicationTaskSetsInitiatorAndPendingStatus() {
        ScopusPublicationUpdate draft = new ScopusPublicationUpdate();
        when(scopusPublicationUpdateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScopusPublicationUpdate saved = facade.createPublicationTask("user@uvt.ro", draft);

        assertEquals("user@uvt.ro", saved.getInitiator());
        assertEquals(Status.PENDING, saved.getStatus());
        assertNotNull(saved.getInitiatedDate());
    }
}
