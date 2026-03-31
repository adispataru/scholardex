package ro.uvt.pokedex.core.service.scopus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.integration.IntegrationErrorCode;
import ro.uvt.pokedex.core.service.integration.IntegrationException;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;
import ro.uvt.pokedex.core.service.scopus.dto.AuthorWorksResponse;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByEidResponse;

import java.time.Instant;
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
    void computeFromDateUsesLatestPublicationDateWithoutForcedOverride() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                new SimpleMeterRegistry(),
                mockAuthorWorksClient()
        );
        ReflectionTestUtils.setField(scheduler, "pageSize", 100);

        var publication = new ro.uvt.pokedex.core.model.scopus.Publication();
        publication.setCoverDate("2024-06-15");
        when(projectionReadService.findAllPublicationsByAuthorsContaining("a1"))
                .thenReturn(List.of(publication));

        String fromDate = (String) ReflectionTestUtils.invokeMethod(scheduler, "computeFromDate", "a1");

        org.junit.jupiter.api.Assertions.assertEquals("2023-06-15", fromDate);
    }

    @Test
    void mapIntegrationExceptionHandlesWrappedHttpStatus() {
        ScopusUpdateScheduler scheduler = scheduler();
        WebClientResponseException nested = WebClientResponseException.create(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"detail\":\"invalid_author_id\"}".getBytes(),
                null
        );

        IntegrationException mapped = (IntegrationException) ReflectionTestUtils.invokeMethod(
                scheduler,
                "mapIntegrationException",
                "authorWorks",
                new RuntimeException("wrapper", nested)
        );

        assertNotNull(mapped);
        assertEquals(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals("authorWorks failed with HTTP 400", mapped.getMessage());
    }

    @Test
    void mapIntegrationExceptionClassifiesDecodingErrors() {
        ScopusUpdateScheduler scheduler = scheduler();

        IntegrationException mapped = (IntegrationException) ReflectionTestUtils.invokeMethod(
                scheduler,
                "mapIntegrationException",
                "authorWorks",
                new RuntimeException("wrapper", new DecodingException("Cannot decode response"))
        );

        assertNotNull(mapped);
        assertEquals(IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD, mapped.getErrorCode());
        assertFalse(mapped.isRetryable());
        assertEquals("authorWorks failed because response payload could not be decoded", mapped.getMessage());
    }

    @Test
    void pollQueuePublicationTaskPassesSchedulerBatchIdIntoCanonicalMaterialization() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);

        ScopusUpdateScheduler scheduler = new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                new SimpleMeterRegistry(),
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
        task.setMaxAttempts(3);
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        var publication = new ro.uvt.pokedex.core.model.scopus.Publication();
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

        verify(canonicalMaterializationService).rebuildFactsAndViews(
                eq("scheduler-publication-task-pub-1"),
                argThat(batchId -> batchId.startsWith("scheduler-publication-task-pub-1-attempt-1"))
        );
        verify(canonicalMaterializationService, never()).rebuildFactsAndViews(eq("scheduler-publication-task-pub-1"));
    }

    @Test
    void pollQueueCitationTaskPassesSchedulerBatchIdIntoCanonicalMaterialization() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);

        WebClient webClient = mockCitationsClient();
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
        ReflectionTestUtils.setField(scheduler, "initialBackoffSeconds", 60L);
        ReflectionTestUtils.setField(scheduler, "maxBackoffSeconds", 3600L);

        ScopusCitationsUpdate task = new ScopusCitationsUpdate();
        task.setId("cit-1");
        task.setScopusId("a1");
        task.setStatus(Status.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        when(publicationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of());
        when(citationTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING)).thenReturn(List.of(task));

        var citedPublication = new ro.uvt.pokedex.core.model.scopus.Publication();
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

        verify(canonicalMaterializationService).rebuildFactsAndViews(
                eq("scheduler-citation-task-cit-1"),
                argThat(batchId -> batchId.startsWith("scheduler-citation-task-cit-1-attempt-1"))
        );
        verify(canonicalMaterializationService, never()).rebuildFactsAndViews(eq("scheduler-citation-task-cit-1"));
    }

    private ScopusUpdateScheduler scheduler() {
        ScopusPublicationUpdateRepository publicationTaskRepo = mock(ScopusPublicationUpdateRepository.class);
        ScopusCitationUpdateRepository citationTaskRepo = mock(ScopusCitationUpdateRepository.class);
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScopusImportEventIngestionService ingestionService = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService canonicalMaterializationService = mock(ScopusCanonicalMaterializationService.class);

        return new ScopusUpdateScheduler(
                publicationTaskRepo,
                citationTaskRepo,
                projectionReadService,
                ingestionService,
                canonicalMaterializationService,
                new SimpleMeterRegistry(),
                WebClient.builder().baseUrl("http://localhost").build()
        );
    }

    @SuppressWarnings("unchecked")
    private WebClient mockAuthorWorksClient() {
        WebClient client = mock(WebClient.class);
        WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestHeadersSpec<?> requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        AuthorWorksResponse response = new AuthorWorksResponse();
        response.setRequest_id("req-1");
        response.setAuthor_id("a1");
        response.setFrom_date("2023-06-15");
        response.setTotal(1);
        response.setItems(List.of(Map.of("eid", "2-s2.0-1")));

        when(client.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/author-works")).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(AuthorWorksResponse.class)).thenReturn(Mono.just(response));
        return client;
    }

    @SuppressWarnings("unchecked")
    private WebClient mockCitationsClient() {
        WebClient client = mock(WebClient.class);
        WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestHeadersSpec<?> requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        CitationsByEidResponse response = new CitationsByEidResponse();
        Map<String, List<Map<String, Object>>> byEid = new LinkedHashMap<>();
        byEid.put("2-s2.0-cited", List.of(Map.of("eid", "2-s2.0-citing", "coverDate", "2025-01-01")));
        response.setRequestId("req-2");
        response.setByEid(byEid);
        response.setPerEidCount(Map.of("2-s2.0-cited", 1));

        when(client.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/citations/by-eid")).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(CitationsByEidResponse.class)).thenReturn(Mono.just(response));
        return client;
    }
}
