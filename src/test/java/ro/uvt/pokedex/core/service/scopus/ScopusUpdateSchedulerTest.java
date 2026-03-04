package ro.uvt.pokedex.core.service.scopus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.importing.ScopusDataService;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

class ScopusUpdateSchedulerTest {

    @Test
    void pollQueueSkipsPublicationTaskWhenNextAttemptInFuture() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusPublicationRepository publicationRepo = mock(ScopusPublicationRepository.class);
        ScopusDataService scopusDataService = mock(ScopusDataService.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScopusCitationRepository citationRepo = mock(ScopusCitationRepository.class);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                publicationRepo,
                scopusDataService,
                citationTaskRepo,
                citationRepo,
                new SimpleMeterRegistry(),
                WebClient.builder().baseUrl("http://localhost").build()
        );
        ReflectionTestUtils.setField(scheduler, "pageSize", 100);

        ScopusPublicationUpdate task = new ScopusPublicationUpdate();
        task.setId("t1");
        task.setStatus(Status.PENDING);
        task.setNextAttemptAt(Instant.now().plusSeconds(300).toString());
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());

        scheduler.pollQueue();

        verify(publicationTaskRepo, never()).save(any(ScopusPublicationUpdate.class));
    }

    @Test
    void computeFromDateUsesLatestPublicationDateWithoutForcedOverride() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusPublicationRepository publicationRepo = mock(ScopusPublicationRepository.class);
        ScopusDataService scopusDataService = mock(ScopusDataService.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScopusCitationRepository citationRepo = mock(ScopusCitationRepository.class);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                publicationRepo,
                scopusDataService,
                citationTaskRepo,
                citationRepo,
                new SimpleMeterRegistry(),
                WebClient.builder().baseUrl("http://localhost").build()
        );
        ReflectionTestUtils.setField(scheduler, "pageSize", 100);

        var publication = new ro.uvt.pokedex.core.model.scopus.Publication();
        publication.setCoverDate("2024-06-15");
        when(publicationRepo.findTopByAuthorsContainsOrderByCoverDateDesc("a1"))
                .thenReturn(java.util.Optional.of(publication));

        String fromDate = (String) ReflectionTestUtils.invokeMethod(scheduler, "computeFromDate", "a1");

        org.junit.jupiter.api.Assertions.assertEquals("2023-06-15", fromDate);
    }
}
