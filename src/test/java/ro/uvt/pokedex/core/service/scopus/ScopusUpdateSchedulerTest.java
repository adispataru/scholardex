package ro.uvt.pokedex.core.service.scopus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;
import ro.uvt.pokedex.core.service.scopus.dto.AuthorWorksRequest;
import ro.uvt.pokedex.core.service.scopus.dto.AuthorWorksResponse;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByEidResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import reactor.core.publisher.Mono;

class ScopusUpdateSchedulerTest {

    @Test
    void pollQueueSkipsPublicationTaskWhenNextAttemptInFuture() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);

        WebClient webClient = mockAuthorWorksClient();
        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                new SimpleMeterRegistry(),
                webClient
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
    void pollQueuePublicationTaskPassesSchedulerBatchIdIntoCanonicalMaterialization() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<ScopusPublicationUpdate> savedTasks = capturePublicationSaves(publicationTaskRepo);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                meterRegistry,
                mockAuthorWorksClient()
        );
        ReflectionTestUtils.setField(scheduler, "pageSize", 100);
        ReflectionTestUtils.setField(scheduler, "defaultMaxAttempts", 3);
        ReflectionTestUtils.setField(scheduler, "initialBackoffSeconds", 60L);
        ReflectionTestUtils.setField(scheduler, "maxBackoffSeconds", 3600L);

        ScopusPublicationUpdate task = new ScopusPublicationUpdate();
        task.setId("pub-1");
        task.setScopusId("a1");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(0);
        task.setNextAttemptAt("stale-next");
        task.setLastErrorCode("STALE_CODE");
        task.setLastErrorMessage("stale message");
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        var publication = new ScholardexPublicationView();
        publication.setCoverDate("2024-06-15");
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a1")).thenReturn(List.of(publication));
        when(ingestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                eq("2-s2.0-1"),
                argThat(batchId -> batchId.startsWith("scheduler-publication-task-pub-1-attempt-1")),
                anyString(),
                eq("json-object"),
                any()
        )).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("event-1"));

        scheduler.pollQueue();

        assertEquals(2, savedTasks.size());
        assertPublicationSnapshot(savedTasks.get(0), Status.IN_PROGRESS, "Starting", null, null,
                "STALE_CODE", "stale message", 1, 3);
        assertPublicationSnapshot(savedTasks.get(1), Status.COMPLETED, "Imported 1 items since 2023-06-15",
                LocalDate.now().toString(), null, null, null, 1, 3);
        assertEquals(1.0, meterRegistry.counter(
                "core.external.scopus_python.calls",
                "operation", "authorWorks",
                "outcome", "success"
        ).count());
        assertEquals(1.0, meterRegistry.counter(
                "core.scheduler.scopus.tasks.processed",
                "taskType", "publication",
                "outcome", "success"
        ).count());
        verify(canonicalMaterializationService).rebuildFactsAndViews(
                eq("scheduler-publication-task-pub-1"),
                argThat(batchId -> batchId.startsWith("scheduler-publication-task-pub-1-attempt-1"))
        );
        verify(canonicalMaterializationService, never()).rebuildFactsAndViews(eq("scheduler-publication-task-pub-1"));
    }

    @Test
    void pollQueuePublicationTaskFiltersPeriodItemsAfterEndYear() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);
        List<ScopusPublicationUpdate> savedTasks = capturePublicationSaves(publicationTaskRepo);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                new SimpleMeterRegistry(),
                mockAuthorWorksClient(authorWorksResponse(List.of(
                        Map.of("eid", "2-s2.0-in", "coverDate", "2023-12-31"),
                        Map.of("eid", "2-s2.0-out", "coverDate", "2024-01-01"),
                        Map.of("eid", "2-s2.0-missing-date"),
                        Map.of("eid", "2-s2.0-malformed-date", "coverDate", "not-a-year"),
                        Map.of("coverDate", "2023-06-01"),
                        Map.of("eid", " ", "coverDate", "2023-07-01")
                ), null))
        );
        ReflectionTestUtils.setField(scheduler, "pageSize", 100);
        ReflectionTestUtils.setField(scheduler, "defaultMaxAttempts", 3);

        ScopusPublicationUpdate task = new ScopusPublicationUpdate();
        task.setId("pub-period");
        task.setScopusId("a1");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setSyncMode("PERIOD");
        task.setStartYear(2020);
        task.setEndYear(2023);
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a1")).thenReturn(List.of(publication("p1", "2022-01-01")));
        when(ingestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                anyString(),
                anyString(),
                anyString(),
                eq("json-object"),
                any()
        )).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("event"));

        scheduler.pollQueue();

        assertEquals(2, savedTasks.size());
        assertPublicationSnapshot(savedTasks.get(1), Status.COMPLETED, "Imported 3 items since 2020-01-01",
                LocalDate.now().toString(), null, null, null, 1, 3);
        verify(ingestionService).ingest(eq(ScopusImportEntityType.PUBLICATION), eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                eq("2-s2.0-in"), anyString(), anyString(), eq("json-object"), any());
        verify(ingestionService, never()).ingest(eq(ScopusImportEntityType.PUBLICATION), eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                eq("2-s2.0-out"), anyString(), anyString(), eq("json-object"), any());
        verify(ingestionService).ingest(eq(ScopusImportEntityType.PUBLICATION), eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                eq("2-s2.0-missing-date"), anyString(), anyString(), eq("json-object"), any());
        verify(ingestionService).ingest(eq(ScopusImportEntityType.PUBLICATION), eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                eq("2-s2.0-malformed-date"), anyString(), anyString(), eq("json-object"), any());
        verify(ingestionService, never()).ingest(eq(ScopusImportEntityType.PUBLICATION), eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                eq(""), anyString(), anyString(), eq("json-object"), any());
    }

    @Test
    void pollQueuePublicationTaskFollowsPaginationCursor() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);
        List<ScopusPublicationUpdate> savedTasks = capturePublicationSaves(publicationTaskRepo);
        WebClient webClient = mockAuthorWorksClient(
                authorWorksResponse(List.of(Map.of("eid", "2-s2.0-page-1")), "cursor-2"),
                authorWorksResponse(List.of(Map.of("eid", "2-s2.0-page-2")), null)
        );

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                new SimpleMeterRegistry(),
                webClient
        );
        ReflectionTestUtils.setField(scheduler, "pageSize", 100);
        ReflectionTestUtils.setField(scheduler, "defaultMaxAttempts", 3);

        ScopusPublicationUpdate task = new ScopusPublicationUpdate();
        task.setId("pub-pages");
        task.setScopusId("a1");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a1")).thenReturn(List.of(publication("p1", "2024-06-15")));
        when(ingestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                anyString(),
                anyString(),
                anyString(),
                eq("json-object"),
                any()
        )).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("event"));

        scheduler.pollQueue();

        assertEquals(2, savedTasks.size());
        assertPublicationSnapshot(savedTasks.get(1), Status.COMPLETED, "Imported 2 items since 2023-06-15",
                LocalDate.now().toString(), null, null, null, 1, 3);
        ArgumentCaptor<AuthorWorksRequest> requestCaptor = ArgumentCaptor.forClass(AuthorWorksRequest.class);
        verify(webClient.post(), times(2)).bodyValue(requestCaptor.capture());
        List<AuthorWorksRequest> requests = requestCaptor.getAllValues();
        assertNull(requests.get(0).getPaging().getCursor());
        assertEquals("cursor-2", requests.get(1).getPaging().getCursor());
        verify(ingestionService).ingest(eq(ScopusImportEntityType.PUBLICATION), eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                eq("2-s2.0-page-1"), anyString(), anyString(), eq("json-object"), any());
        verify(ingestionService).ingest(eq(ScopusImportEntityType.PUBLICATION), eq("SCOPUS_PYTHON_AUTHOR_WORKS"),
                eq("2-s2.0-page-2"), anyString(), anyString(), eq("json-object"), any());
    }

    @Test
    void pollQueueCitationTaskPassesSchedulerBatchIdIntoCanonicalMaterialization() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<ScopusCitationsUpdate> savedTasks = captureCitationSaves(citationTaskRepo);

        WebClient webClient = mockCitationsClient();
        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                meterRegistry,
                webClient
        );
        ReflectionTestUtils.setField(scheduler, "pageSize", 100);
        ReflectionTestUtils.setField(scheduler, "defaultMaxAttempts", 3);
        ReflectionTestUtils.setField(scheduler, "initialBackoffSeconds", 60L);
        ReflectionTestUtils.setField(scheduler, "maxBackoffSeconds", 3600L);

        ScopusCitationsUpdate task = new ScopusCitationsUpdate();
        task.setId("cit-1");
        task.setScopusId("a1");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setNextAttemptAt("stale-next");
        task.setLastErrorCode("STALE_CODE");
        task.setLastErrorMessage("stale message");
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));

        var citedPublication = new ScholardexPublicationView();
        citedPublication.setId("spub_1");
        citedPublication.setEid("2-s2.0-cited");
        citedPublication.setCoverDate("2024-01-10");
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a1")).thenReturn(List.of(citedPublication));
        when(projectionReadService.findAllCitationsByCitedIdIn(List.of("spub_1"))).thenReturn(List.of());

        when(ingestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq("SCOPUS_PYTHON_CITATIONS_PUBLICATION"),
                eq("2-s2.0-citing"),
                argThat(batchId -> batchId.startsWith("scheduler-citation-task-cit-1-attempt-1")),
                anyString(),
                eq("json-object"),
                any()
        )).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("event-2"));
        when(ingestionService.ingest(
                eq(ScopusImportEntityType.CITATION),
                eq("SCOPUS_PYTHON_CITATIONS_EDGE"),
                eq("2-s2.0-cited->2-s2.0-citing"),
                argThat(batchId -> batchId.startsWith("scheduler-citation-task-cit-1-attempt-1")),
                anyString(),
                eq("json-object"),
                any()
        )).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("event-3"));

        scheduler.pollQueue();

        assertEquals(2, savedTasks.size());
        assertCitationSnapshot(savedTasks.get(0), Status.IN_PROGRESS, "Starting citations update", null, null,
                "STALE_CODE", "stale message", 1, 3);
        assertCitationSnapshot(savedTasks.get(1), Status.COMPLETED,
                "Author a1: imported/updated 1 citing publications and 1 citation links.",
                LocalDate.now().toString(), null, null, null, 1, 3);
        assertEquals(1.0, meterRegistry.counter(
                "core.external.scopus_python.calls",
                "operation", "citationsByEid",
                "outcome", "success"
        ).count());
        assertEquals(1.0, meterRegistry.counter(
                "core.scheduler.scopus.tasks.processed",
                "taskType", "citation",
                "outcome", "success"
        ).count());
        verify(canonicalMaterializationService).rebuildFactsAndViews(
                eq("scheduler-citation-task-cit-1"),
                argThat(batchId -> batchId.startsWith("scheduler-citation-task-cit-1-attempt-1"))
        );
        verify(canonicalMaterializationService, never()).rebuildFactsAndViews(eq("scheduler-citation-task-cit-1"));
    }

    @Test
    void pollQueueCitationTaskSkipsEmptyAndInvalidCitationItems() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);
        List<ScopusCitationsUpdate> savedTasks = captureCitationSaves(citationTaskRepo);

        CitationsByEidResponse response = new CitationsByEidResponse();
        Map<String, List<Map<String, Object>>> byEid = new LinkedHashMap<>();
        byEid.put("2-s2.0-null-list", null);
        byEid.put("2-s2.0-empty-list", List.of());
        byEid.put("2-s2.0-invalid-items", List.of(
                Map.of("title", "missing eid"),
                Map.of("eid", " "),
                Map.of("eid", "2-s2.0-valid", "coverDate", "2025-01-01")
        ));
        response.setRequestId("req-edge");
        response.setByEid(byEid);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                new SimpleMeterRegistry(),
                mockCitationsClient(Mono.just(response))
        );
        ReflectionTestUtils.setField(scheduler, "defaultMaxAttempts", 3);

        ScopusCitationsUpdate task = new ScopusCitationsUpdate();
        task.setId("cit-edge");
        task.setScopusId("a1");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setNextAttemptAt("stale-next");
        task.setLastErrorCode("STALE_CODE");
        task.setLastErrorMessage("stale message");
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a1")).thenReturn(List.of(publication("p1", "2-s2.0-cited", "2024-01-10")));
        when(projectionReadService.findAllCitationsByCitedIdIn(List.of("p1"))).thenReturn(List.of());
        when(ingestionService.ingest(
                any(ScopusImportEntityType.class),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                eq("json-object"),
                any()
        )).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("event"));

        scheduler.pollQueue();

        assertEquals(2, savedTasks.size());
        assertCitationSnapshot(savedTasks.get(1), Status.COMPLETED,
                "Author a1: imported/updated 1 citing publications and 1 citation links.",
                LocalDate.now().toString(), null, null, null, 1, 3);
        verify(ingestionService).ingest(eq(ScopusImportEntityType.PUBLICATION),
                eq("SCOPUS_PYTHON_CITATIONS_PUBLICATION"),
                eq("2-s2.0-valid"),
                anyString(),
                anyString(),
                eq("json-object"),
                any());
        verify(ingestionService).ingest(eq(ScopusImportEntityType.CITATION),
                eq("SCOPUS_PYTHON_CITATIONS_EDGE"),
                eq("2-s2.0-invalid-items->2-s2.0-valid"),
                anyString(),
                anyString(),
                eq("json-object"),
                any());
        verify(ingestionService, times(2)).ingest(
                any(ScopusImportEntityType.class),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                eq("json-object"),
                any());
        verify(canonicalMaterializationService).rebuildFactsAndViews(
                eq("scheduler-citation-task-cit-edge"),
                argThat(batchId -> batchId.startsWith("scheduler-citation-task-cit-edge-attempt-1")));
    }

    @Test
    void pollQueueCitationTaskCompletesWithoutPythonCallWhenAuthorHasNoPublications() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);
        WebClient webClient = mock(WebClient.class);
        List<ScopusCitationsUpdate> savedTasks = captureCitationSaves(citationTaskRepo);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                new SimpleMeterRegistry(),
                webClient
        );
        ReflectionTestUtils.setField(scheduler, "defaultMaxAttempts", 3);

        ScopusCitationsUpdate task = new ScopusCitationsUpdate();
        task.setId("cit-empty");
        task.setScopusId("a-empty");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(0);
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a-empty")).thenReturn(List.of());

        scheduler.pollQueue();

        assertEquals(2, savedTasks.size());
        assertCitationSnapshot(savedTasks.get(0), Status.IN_PROGRESS, "Starting citations update", null, null, null, null, 1, 3);
        assertCitationSnapshot(savedTasks.get(1), Status.COMPLETED,
                "No publications found for author a-empty, nothing to update.",
                LocalDate.now().toString(), null, null, null, 1, 3);
        verify(webClient, never()).post();
        verifyNoInteractions(ingestionService, canonicalMaterializationService);
        verify(projectionReadService, never()).findAllCitationsByCitedIdIn(anyList());
    }

    @Test
    void pollQueuePublicationTaskSchedulesRetryWhenPythonCallFailsWithRetryableIntegrationError() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<ScopusPublicationUpdate> savedTasks = capturePublicationSaves(publicationTaskRepo);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                meterRegistry,
                mockAuthorWorksClient(Mono.error(new ro.uvt.pokedex.core.service.integration.IntegrationException(
                        ro.uvt.pokedex.core.service.integration.IntegrationErrorCode.EXTERNAL_TIMEOUT,
                        true,
                        "temporary outage"
                )))
        );
        ReflectionTestUtils.setField(scheduler, "pageSize", 100);
        ReflectionTestUtils.setField(scheduler, "defaultMaxAttempts", 3);
        ReflectionTestUtils.setField(scheduler, "initialBackoffSeconds", 60L);
        ReflectionTestUtils.setField(scheduler, "maxBackoffSeconds", 3600L);

        ScopusPublicationUpdate task = new ScopusPublicationUpdate();
        task.setId("pub-retry");
        task.setScopusId("a1");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a1")).thenReturn(List.of(publication("p1", "2024-06-15")));

        scheduler.pollQueue();

        assertEquals(2, savedTasks.size());
        assertPublicationSnapshot(savedTasks.get(0), Status.IN_PROGRESS, "Starting", null, null, null, null, 1, 3);
        ScopusPublicationUpdate failure = savedTasks.get(1);
        assertEquals(Status.PENDING, failure.getStatus());
        assertEquals("RETRY_SCHEDULED: temporary outage", failure.getMessage());
        assertNull(failure.getExecutionDate());
        assertEquals("EXTERNAL_TIMEOUT", failure.getLastErrorCode());
        assertEquals("temporary outage", failure.getLastErrorMessage());
        assertNotNull(failure.getNextAttemptAt());
        assertEquals(1, failure.getAttemptCount());
        assertEquals(3, failure.getMaxAttempts());
        assertEquals(1.0, meterRegistry.counter(
                "core.scheduler.scopus.tasks.processed",
                "taskType", "publication",
                "outcome", "failure"
        ).count());
        assertEquals(1.0, meterRegistry.counter(
                "core.external.scopus_python.calls",
                "operation", "authorWorks",
                "outcome", "failure"
        ).count());
        verifyNoInteractions(ingestionService, canonicalMaterializationService);
    }

    @Test
    void pollQueueCitationTaskMarksTerminalFailureWhenPythonReturnsEmptyResponse() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<ScopusCitationsUpdate> savedTasks = captureCitationSaves(citationTaskRepo);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                meterRegistry,
                mockCitationsClient(Mono.empty())
        );
        ReflectionTestUtils.setField(scheduler, "defaultMaxAttempts", 3);
        ReflectionTestUtils.setField(scheduler, "initialBackoffSeconds", 60L);
        ReflectionTestUtils.setField(scheduler, "maxBackoffSeconds", 3600L);

        ScopusCitationsUpdate task = new ScopusCitationsUpdate();
        task.setId("cit-fail");
        task.setScopusId("a1");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setNextAttemptAt("stale-next");
        task.setLastErrorCode("STALE_CODE");
        task.setLastErrorMessage("stale message");
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a1")).thenReturn(List.of(publication("p1", "2-s2.0-cited", "2024-01-10")));
        when(projectionReadService.findAllCitationsByCitedIdIn(List.of("p1"))).thenReturn(List.of());

        scheduler.pollQueue();

        assertEquals(2, savedTasks.size());
        assertCitationSnapshot(savedTasks.get(0), Status.IN_PROGRESS, "Starting citations update", null, null,
                "STALE_CODE", "stale message", 1, 3);
        ScopusCitationsUpdate failure = savedTasks.get(1);
        assertEquals(Status.FAILED, failure.getStatus());
        assertEquals("FAILED: Python citations service returned empty response", failure.getMessage());
        assertEquals(LocalDate.now().toString(), failure.getExecutionDate());
        assertNull(failure.getNextAttemptAt());
        assertEquals("EXTERNAL_BAD_PAYLOAD", failure.getLastErrorCode());
        assertEquals("Python citations service returned empty response", failure.getLastErrorMessage());
        assertEquals(1, failure.getAttemptCount());
        assertEquals(3, failure.getMaxAttempts());
        assertEquals(1.0, meterRegistry.counter(
                "core.scheduler.scopus.tasks.processed",
                "taskType", "citation",
                "outcome", "failure"
        ).count());
        assertEquals(1.0, meterRegistry.counter(
                "core.external.scopus_python.calls",
                "operation", "citationsByEid",
                "outcome", "failure"
        ).count());
        verifyNoInteractions(ingestionService, canonicalMaterializationService);
    }

    @Test
    void pollQueuePublicationTaskMarksTerminalFailureWhenPythonReturnsEmptyResponse() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<ScopusPublicationUpdate> savedTasks = capturePublicationSaves(publicationTaskRepo);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                meterRegistry,
                mockAuthorWorksClient(Mono.empty())
        );
        ReflectionTestUtils.setField(scheduler, "pageSize", 100);
        ReflectionTestUtils.setField(scheduler, "defaultMaxAttempts", 3);
        ReflectionTestUtils.setField(scheduler, "initialBackoffSeconds", 60L);
        ReflectionTestUtils.setField(scheduler, "maxBackoffSeconds", 3600L);

        ScopusPublicationUpdate task = new ScopusPublicationUpdate();
        task.setId("pub-fail");
        task.setScopusId("a1");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a1")).thenReturn(List.of(publication("p1", "2024-06-15")));

        scheduler.pollQueue();

        assertEquals(2, savedTasks.size());
        assertPublicationSnapshot(savedTasks.get(0), Status.IN_PROGRESS, "Starting", null, null, null, null, 1, 3);
        ScopusPublicationUpdate failure = savedTasks.get(1);
        assertEquals(Status.FAILED, failure.getStatus());
        assertEquals("FAILED: Python author works service returned empty response", failure.getMessage());
        assertEquals(LocalDate.now().toString(), failure.getExecutionDate());
        assertNull(failure.getNextAttemptAt());
        assertEquals("EXTERNAL_BAD_PAYLOAD", failure.getLastErrorCode());
        assertEquals("Python author works service returned empty response", failure.getLastErrorMessage());
        assertEquals(1, failure.getAttemptCount());
        assertEquals(3, failure.getMaxAttempts());
        assertEquals(1.0, meterRegistry.counter(
                "core.scheduler.scopus.tasks.processed",
                "taskType", "publication",
                "outcome", "failure"
        ).count());
        assertEquals(1.0, meterRegistry.counter(
                "core.external.scopus_python.calls",
                "operation", "authorWorks",
                "outcome", "failure"
        ).count());
        verifyNoInteractions(ingestionService, canonicalMaterializationService);
    }

    @SuppressWarnings("unchecked")
    private WebClient mockAuthorWorksClient() {
        return mockAuthorWorksClient(authorWorksResponse(List.of(Map.of("eid", "2-s2.0-1")), null));
    }

    @SuppressWarnings("unchecked")
    private WebClient mockAuthorWorksClient(Mono<AuthorWorksResponse> responseMono) {
        return mockAuthorWorksClient(responseMono, (Mono<AuthorWorksResponse>[]) new Mono[0]);
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private final WebClient mockAuthorWorksClient(Mono<AuthorWorksResponse> firstResponse, Mono<AuthorWorksResponse>... additionalResponses) {
        WebClient client = mock(WebClient.class);
        WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestHeadersSpec<?> requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(client.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/author-works")).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(AuthorWorksResponse.class)).thenReturn(firstResponse, additionalResponses);
        return client;
    }

    private Mono<AuthorWorksResponse> authorWorksResponse(List<Map<String, Object>> items, String nextCursor) {
        AuthorWorksResponse response = new AuthorWorksResponse();
        response.setRequest_id("req-1");
        response.setAuthor_id("a1");
        response.setFrom_date("2023-06-15");
        response.setTotal(items.size());
        response.setItems(items);
        response.setNext_cursor(nextCursor);
        return Mono.just(response);
    }

    @SuppressWarnings("unchecked")
    private WebClient mockCitationsClient() {
        CitationsByEidResponse response = new CitationsByEidResponse();
        Map<String, List<Map<String, Object>>> byEid = new LinkedHashMap<>();
        byEid.put("2-s2.0-cited", List.of(Map.of("eid", "2-s2.0-citing", "coverDate", "2025-01-01")));
        response.setRequestId("req-2");
        response.setByEid(byEid);
        response.setPerEidCount(Map.of("2-s2.0-cited", 1));
        return mockCitationsClient(Mono.just(response));
    }

    @SuppressWarnings("unchecked")
    private WebClient mockCitationsClient(Mono<CitationsByEidResponse> responseMono) {
        WebClient client = mock(WebClient.class);
        WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestHeadersSpec<?> requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(client.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/citations/by-eid")).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(CitationsByEidResponse.class)).thenReturn(responseMono);
        return client;
    }

    private List<ScopusPublicationUpdate> capturePublicationSaves(ScopusPublicationUpdateRepository repository) {
        List<ScopusPublicationUpdate> snapshots = new ArrayList<>();
        when(repository.save(any(ScopusPublicationUpdate.class))).thenAnswer(invocation -> {
            ScopusPublicationUpdate task = invocation.getArgument(0);
            snapshots.add(copyPublicationTask(task));
            return task;
        });
        return snapshots;
    }

    private List<ScopusCitationsUpdate> captureCitationSaves(ScopusCitationUpdateRepository repository) {
        List<ScopusCitationsUpdate> snapshots = new ArrayList<>();
        when(repository.save(any(ScopusCitationsUpdate.class))).thenAnswer(invocation -> {
            ScopusCitationsUpdate task = invocation.getArgument(0);
            snapshots.add(copyCitationTask(task));
            return task;
        });
        return snapshots;
    }

    private ScopusPublicationUpdate copyPublicationTask(ScopusPublicationUpdate source) {
        ScopusPublicationUpdate copy = new ScopusPublicationUpdate();
        copy.setId(source.getId());
        copy.setScopusId(source.getScopusId());
        copy.setSyncMode(source.getSyncMode());
        copy.setStartYear(source.getStartYear());
        copy.setEndYear(source.getEndYear());
        copyTaskFields(source, copy);
        return copy;
    }

    private ScopusCitationsUpdate copyCitationTask(ScopusCitationsUpdate source) {
        ScopusCitationsUpdate copy = new ScopusCitationsUpdate();
        copy.setId(source.getId());
        copy.setScopusId(source.getScopusId());
        copy.setSyncMode(source.getSyncMode());
        copy.setStartYear(source.getStartYear());
        copy.setEndYear(source.getEndYear());
        copyTaskFields(source, copy);
        return copy;
    }

    private void copyTaskFields(ro.uvt.pokedex.core.model.tasks.Task source, ro.uvt.pokedex.core.model.tasks.Task target) {
        target.setInitiator(source.getInitiator());
        target.setInitiatedDate(source.getInitiatedDate());
        target.setExecutionDate(source.getExecutionDate());
        target.setStatus(source.getStatus());
        target.setMessage(source.getMessage());
        target.setAttemptCount(source.getAttemptCount());
        target.setMaxAttempts(source.getMaxAttempts());
        target.setNextAttemptAt(source.getNextAttemptAt());
        target.setLastErrorCode(source.getLastErrorCode());
        target.setLastErrorMessage(source.getLastErrorMessage());
    }

    private ScholardexPublicationView publication(String id, String coverDate) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setCoverDate(coverDate);
        return publication;
    }

    private ScholardexPublicationView publication(String id, String eid, String coverDate) {
        ScholardexPublicationView publication = publication(id, coverDate);
        publication.setEid(eid);
        return publication;
    }

    private void assertPublicationSnapshot(
            ScopusPublicationUpdate task,
            Status status,
            String message,
            String executionDate,
            String nextAttemptAt,
            String lastErrorCode,
            String lastErrorMessage,
            int attemptCount,
            int maxAttempts
    ) {
        assertEquals(status, task.getStatus());
        assertEquals(message, task.getMessage());
        assertEquals(executionDate, task.getExecutionDate());
        assertEquals(nextAttemptAt, task.getNextAttemptAt());
        assertEquals(lastErrorCode, task.getLastErrorCode());
        assertEquals(lastErrorMessage, task.getLastErrorMessage());
        assertEquals(attemptCount, task.getAttemptCount());
        assertEquals(maxAttempts, task.getMaxAttempts());
    }

    private void assertCitationSnapshot(
            ScopusCitationsUpdate task,
            Status status,
            String message,
            String executionDate,
            String nextAttemptAt,
            String lastErrorCode,
            String lastErrorMessage,
            int attemptCount,
            int maxAttempts
    ) {
        assertEquals(status, task.getStatus());
        assertEquals(message, task.getMessage());
        assertEquals(executionDate, task.getExecutionDate());
        assertEquals(nextAttemptAt, task.getNextAttemptAt());
        assertEquals(lastErrorCode, task.getLastErrorCode());
        assertEquals(lastErrorMessage, task.getLastErrorMessage());
        assertEquals(attemptCount, task.getAttemptCount());
        assertEquals(maxAttempts, task.getMaxAttempts());
    }
}
