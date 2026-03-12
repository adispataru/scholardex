package ro.uvt.pokedex.core.service.scopus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.integration.IntegrationErrorCode;
import ro.uvt.pokedex.core.service.integration.IntegrationException;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;
import ro.uvt.pokedex.core.service.scopus.dto.AuthorWorksRequest;
import ro.uvt.pokedex.core.service.scopus.dto.AuthorWorksResponse;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByEidRequest;
import ro.uvt.pokedex.core.service.scopus.dto.CitationsByEidResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(value = "core.scopus.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ScopusUpdateScheduler {

    private final ScopusPublicationUpdateRepository taskRepo;
    private final ScopusCitationUpdateRepository citationsTaskRepo;
    private final ScholardexProjectionReadService scopusProjectionReadService;
    private final ScopusImportEventIngestionService importEventIngestionService;
    private final ScopusCanonicalMaterializationService canonicalMaterializationService;
    private final MeterRegistry meterRegistry;

    private final WebClient scopusPythonClient;
    private final ObjectMapper mapper = new ObjectMapper();


    @Value("${scopus.update.page-size:100}")
    private int pageSize;
    @Value("${scopus.update.max-attempts:3}")
    private int defaultMaxAttempts;
    @Value("${scopus.update.retry.initial-backoff-seconds:60}")
    private long initialBackoffSeconds;
    @Value("${scopus.update.retry.max-backoff-seconds:3600}")
    private long maxBackoffSeconds;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId Z = ZoneId.systemDefault();



    @Scheduled(fixedDelayString = "${scopus.update.poll-ms:60000}")
    public void pollQueue() {
        Timer.Sample pollTimer = Timer.start(meterRegistry);
        String batchTaskId = "batch-" + UUID.randomUUID();
        AutoCloseable batchContext = SchedulerCorrelationSupport.withSchedulerContext(
                "scopus.update.poll",
                batchTaskId,
                "start"
        );
        long startedAt = System.currentTimeMillis();
        int publicationTasks = 0;
        int citationTasks = 0;
        log.info("Scopus scheduler poll started: batchTaskId={}", batchTaskId);
        try {
            publicationTasks = processPublicationTasks();
            citationTasks = processCitationTasks();
            MDC.put("phase", "complete");
            meterRegistry.counter("core.scheduler.scopus.tasks.processed", "taskType", "publication", "outcome", "success")
                    .increment(publicationTasks);
            meterRegistry.counter("core.scheduler.scopus.tasks.processed", "taskType", "citation", "outcome", "success")
                    .increment(citationTasks);
            log.info("Scopus scheduler poll completed: batchTaskId={}, publicationTasks={}, citationTasks={}, durationMs={}",
                    batchTaskId, publicationTasks, citationTasks, System.currentTimeMillis() - startedAt);
            pollTimer.stop(meterRegistry.timer("core.scheduler.scopus.poll.duration", "outcome", "success"));
        } catch (Exception e) {
            MDC.put("phase", "failed");
            pollTimer.stop(meterRegistry.timer("core.scheduler.scopus.poll.duration", "outcome", "failure"));
            log.error("Scopus scheduler poll failed: batchTaskId={}, durationMs={}",
                    batchTaskId, System.currentTimeMillis() - startedAt, e);
            throw e;
        } finally {
            closeContext(batchContext);
        }
    }

    private int processPublicationTasks() {
        List<ScopusPublicationUpdate> tasks =
                taskRepo.findByStatusOrderByInitiatedDate(Status.PENDING);

        if (tasks.isEmpty()) return 0;

        int processedTasks = 0;

        for (ScopusPublicationUpdate t : tasks) {
            if (!isReadyForAttempt(t.getNextAttemptAt())) {
                continue;
            }
            AutoCloseable taskContext = SchedulerCorrelationSupport.withSchedulerContext(
                    "scopus.publication.update",
                    String.valueOf(t.getId()),
                    "start"
            );
            long taskStartedAt = System.currentTimeMillis();
            try {
                runOnePublicationUpdate(t);
                processedTasks++;
                MDC.put("phase", "complete");
                log.info("Publication task {} completed in {} ms", t.getId(), System.currentTimeMillis() - taskStartedAt);
            } catch (Exception e) {
                MDC.put("phase", "failed");
                meterRegistry.counter("core.scheduler.scopus.tasks.processed", "taskType", "publication", "outcome", "failure")
                        .increment();
                handlePublicationTaskFailure(t, e);
            } finally {
                closeContext(taskContext);
            }
        }
        return processedTasks;
    }

    private void runOnePublicationUpdate(ScopusPublicationUpdate task) {
        MDC.put("phase", "progress");
        task.setAttemptCount(task.getAttemptCount() + 1);
        if (task.getMaxAttempts() <= 0) {
            task.setMaxAttempts(defaultMaxAttempts);
        }
        task.setStatus(Status.IN_PROGRESS);
        task.setMessage("Starting");
        task.setNextAttemptAt(null);
        taskRepo.save(task);

        final String authorScopusId = task.getScopusId();
        String fromDate = computeFromDate(authorScopusId);

        String cursor = null;
        int imported = 0;
        String batchId = "scheduler-publication-task-" + task.getId() + "-attempt-" + task.getAttemptCount();

        do {
            AuthorWorksRequest req = buildRequest(authorScopusId, fromDate, cursor);
            AuthorWorksResponse resp = callPython(req);

            if (resp.getItems() != null) {
                for (Map<String, Object> item : resp.getItems()) {
                    String sourceRecordId = text(item, "eid");
                    if (sourceRecordId == null || sourceRecordId.isBlank()) {
                        continue;
                    }
                    ScopusImportEventIngestionService.EventIngestionOutcome outcome = importEventIngestionService.ingest(
                            ScopusImportEntityType.PUBLICATION,
                            "SCOPUS_PYTHON_AUTHOR_WORKS",
                            sourceRecordId,
                            batchId,
                            req.getRequest_id(),
                            "json-object",
                            mapper.valueToTree(item)
                    );
                    if (outcome.imported()) {
                        imported++;
                    }
                }
            }

            cursor = resp.getNext_cursor();
            log.debug("Publication task {} progress: imported={}, nextCursorPresent={}",
                    task.getId(), imported, cursor != null && !cursor.isBlank());
        } while (cursor != null && !cursor.isBlank());

        MDC.put("phase", "complete");
        task.setStatus(Status.COMPLETED);
        task.setMessage("Imported " + imported + " items since " + fromDate);
        task.setExecutionDate(LocalDate.now(Z).toString());
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);
        task.setNextAttemptAt(null);
        taskRepo.save(task);
        canonicalMaterializationService.rebuildFactsAndViews("scheduler-publication-task-" + task.getId(), batchId);
    }



    private int processCitationTasks() {
        List<ScopusCitationsUpdate> tasks =
                citationsTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING);

        if (tasks.isEmpty()) return 0;

        int processedTasks = 0;

        for (ScopusCitationsUpdate t : tasks) {
            if (!isReadyForAttempt(t.getNextAttemptAt())) {
                continue;
            }
            AutoCloseable taskContext = SchedulerCorrelationSupport.withSchedulerContext(
                    "scopus.citation.update",
                    String.valueOf(t.getId()),
                    "start"
            );
            long taskStartedAt = System.currentTimeMillis();
            try {
                runOneCitationsUpdate(t);
                processedTasks++;
                MDC.put("phase", "complete");
                log.info("Citations task {} completed in {} ms", t.getId(), System.currentTimeMillis() - taskStartedAt);
            } catch (Exception e) {
                MDC.put("phase", "failed");
                meterRegistry.counter("core.scheduler.scopus.tasks.processed", "taskType", "citation", "outcome", "failure")
                        .increment();
                handleCitationTaskFailure(t, e);
            } finally {
                closeContext(taskContext);
            }
        }
        return processedTasks;
    }

    private void runOneCitationsUpdate(ScopusCitationsUpdate task) {
        MDC.put("phase", "progress");
        task.setAttemptCount(task.getAttemptCount() + 1);
        if (task.getMaxAttempts() <= 0) {
            task.setMaxAttempts(defaultMaxAttempts);
        }
        task.setStatus(Status.IN_PROGRESS);
        task.setMessage("Starting citations update");
        task.setNextAttemptAt(null);
        citationsTaskRepo.save(task);

        final String authorScopusId = task.getScopusId();

        // 1) compute last citation date per EID **for this author only**
        Map<String, String> eidLastDate = computeEidLastCitationDatesForAuthor(authorScopusId);
        if (eidLastDate.isEmpty()) {
            task.setStatus(Status.COMPLETED);
            task.setMessage("No publications found for author " + authorScopusId + ", nothing to update.");
            task.setExecutionDate(LocalDate.now(Z).toString());
            task.setLastErrorCode(null);
            task.setLastErrorMessage(null);
            task.setNextAttemptAt(null);
            citationsTaskRepo.save(task);
            return;
        }

        // 2) prepare request
        CitationsByEidRequest req = new CitationsByEidRequest();
        req.setRequestId(UUID.randomUUID().toString());
        req.setEidLastDate(eidLastDate);
        req.setPageSizePerEid(100);
        req.setIncludeEnrichment(true);

        // 3) call Python service
        CitationsByEidResponse resp = callPythonCitations(req);
        log.debug("Citations task {} progress: requestId={}, eids={}",
                task.getId(), req.getRequestId(), req.getEidLastDate().size());

        int importedPublications = 0;
        int createdCitations = 0;
        String batchId = "scheduler-citation-task-" + task.getId() + "-attempt-" + task.getAttemptCount();

        Map<String, List<Map<String, Object>>> byEid = resp.getByEid();
        if (byEid != null) {
            for (Map.Entry<String, List<Map<String, Object>>> entry : byEid.entrySet()) {
                String citedEid = entry.getKey();
                List<Map<String, Object>> citingItems = entry.getValue();

                if (citingItems == null || citingItems.isEmpty()) continue;

                for (Map<String, Object> citingItem : citingItems) {
                    String citingEid = text(citingItem, "eid");
                    if (citingEid == null || citingEid.isBlank()) {
                        continue;
                    }
                    JsonNode item = mapper.valueToTree(citingItem);

                    ScopusImportEventIngestionService.EventIngestionOutcome publicationOutcome = importEventIngestionService.ingest(
                            ScopusImportEntityType.PUBLICATION,
                            "SCOPUS_PYTHON_CITATIONS_PUBLICATION",
                            citingEid,
                            batchId,
                            req.getRequestId(),
                            "json-object",
                            item
                    );
                    if (publicationOutcome.imported()) {
                        importedPublications++;
                    }

                    Map<String, Object> citationPayload = new LinkedHashMap<>();
                    citationPayload.put("citedEid", citedEid);
                    citationPayload.put("citingEid", citingEid);

                    ScopusImportEventIngestionService.EventIngestionOutcome citationOutcome = importEventIngestionService.ingest(
                            ScopusImportEntityType.CITATION,
                            "SCOPUS_PYTHON_CITATIONS_EDGE",
                            citedEid + "->" + citingEid,
                            batchId,
                            req.getRequestId(),
                            "json-object",
                            citationPayload
                    );
                    if (citationOutcome.imported()) {
                        createdCitations++;
                    }
                }
            }
        }

        task.setStatus(Status.COMPLETED);
        task.setMessage("Author " + authorScopusId + ": imported/updated " +
                importedPublications + " citing publications and " + createdCitations + " citation links.");
        task.setExecutionDate(LocalDate.now(Z).toString());
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);
        task.setNextAttemptAt(null);
        citationsTaskRepo.save(task);
        canonicalMaterializationService.rebuildFactsAndViews("scheduler-citation-task-" + task.getId(), batchId);
    }

    private void closeContext(AutoCloseable context) {
        try {
            context.close();
        } catch (Exception e) {
            log.warn("Failed to close scheduler correlation context cleanly", e);
        }
    }

    private Map<String, String> computeEidLastCitationDatesForAuthor(String authorScopusId) {
        // 1) All publications by this author
        List<Publication> authorPubs = scopusProjectionReadService.findAllPublicationsByAuthorsContaining(authorScopusId);
        if (authorPubs.isEmpty()) {
            return Collections.emptyMap();
        }

        // id -> Publication for author’s publications (for cited side)
        Map<String, Publication> byId = new HashMap<>();
        for (Publication p : authorPubs) {
            if (p.getId() != null) {
                byId.put(p.getId(), p);
            }
        }

        // 2) All citations where any of these pubs is the **cited** one
        List<String> citedIds = authorPubs.stream()
                .map(Publication::getId)
                .filter(Objects::nonNull)
                .toList();

        List<Citation> citations = scopusProjectionReadService.findAllCitationsByCitedIdIn(citedIds);

        // 3) Load all citing publications for those citations (more efficient than findAll())
        Set<String> citingIds = citations.stream()
                .map(Citation::getCitingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Publication> citingPubs = citingIds.isEmpty()
                ? Collections.emptyList()
                : scopusProjectionReadService.findAllPublicationsByIdIn(new ArrayList<>(citingIds));

        Map<String, Publication> citingById = new HashMap<>();
        for (Publication p : citingPubs) {
            if (p.getId() != null) {
                citingById.put(p.getId(), p);
            }
        }

        // 4) Initialize map with all author EIDs, default last-date = null
        Map<String, String> lastDateByEid = new HashMap<>();
        for (Publication p : authorPubs) {
            if (p.getEid() != null) {
                lastDateByEid.put(p.getEid(), null);
            }
        }

        // 5) For each citation, update the last citing date for the cited EID
        for (Citation c : citations) {
            Publication cited = byId.get(c.getCitedId());
            Publication citing = citingById.get(c.getCitingId());
            if (cited == null || citing == null) {
                continue;
            }

            String citingCover = citing.getCoverDate();
            if (citingCover == null || citingCover.isBlank()) {
                continue;
            }

//            LocalDate citingDate = parseCoverDate(citingCover);
            Optional<LocalDate> citingDateOpt = parseCoverDate(citingCover);
            if (citingDateOpt.isEmpty()) {
                continue;
            }
            LocalDate citingDate = citingDateOpt.get();
            String citedEid = cited.getEid();
            if (citedEid == null) continue;

            String existing = lastDateByEid.get(citedEid);
            Optional<LocalDate> existingDate = parseCoverDate(existing);
            if (existingDate.isEmpty() || existingDate.get().isBefore(citingDate)) {
                lastDateByEid.put(citedEid, citingDate.format(ISO));
            }
        }

        return lastDateByEid;
    }




    private String computeFromDate(String authorScopusId) {
        List<Publication> publications = scopusProjectionReadService.findAllPublicationsByAuthorsContaining(authorScopusId);
        LocalDate base = publications.stream()
                .map(Publication::getCoverDate)
                .map(this::parseCoverDate)
                .flatMap(Optional::stream)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.now(Z).minusYears(5));
        return base.minusYears(1).format(ISO);
    }

    private Optional<LocalDate> parseCoverDate(String s) {
        try {
            if (s == null || s.isBlank()) return Optional.empty();
            if (s.length() == 10) return Optional.of(LocalDate.parse(s));
            if (s.length() == 7)  return Optional.of(LocalDate.parse(s + "-01"));
            if (s.length() == 4)  return Optional.of(LocalDate.parse(s + "-01-01"));
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null ? null : text.trim();
    }

    private String text(Map<String, Object> map, String field) {
        if (map == null || field == null) {
            return null;
        }
        Object value = map.get(field);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text.trim();
    }

    private CitationsByEidResponse callPythonCitations(CitationsByEidRequest req) {
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            CitationsByEidResponse response = scopusPythonClient.post()
                    .uri("/v1/citations/by-eid")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(CitationsByEidResponse.class)
                    .onErrorResume(ex -> {
                        IntegrationException mapped = mapIntegrationException("citationsByEid", ex);
                        log.error("Python citations service call failed: code={}, retryable={}, message={}",
                                mapped.getErrorCode(), mapped.isRetryable(), mapped.getMessage());
                        return Mono.error(mapped);
                    })
                    .block();
            if (response == null) {
                throw new IntegrationException(
                        IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                        false,
                        "Python citations service returned empty response"
                );
            }
            meterRegistry.counter("core.external.scopus_python.calls", "operation", "citationsByEid", "outcome", "success")
                    .increment();
            timer.stop(meterRegistry.timer("core.external.scopus_python.duration", "operation", "citationsByEid", "outcome", "success"));
            return response;
        } catch (RuntimeException ex) {
            meterRegistry.counter("core.external.scopus_python.calls", "operation", "citationsByEid", "outcome", "failure")
                    .increment();
            timer.stop(meterRegistry.timer("core.external.scopus_python.duration", "operation", "citationsByEid", "outcome", "failure"));
            throw ex;
        }
    }


    private AuthorWorksRequest buildRequest(String authorId, String fromDate, String cursor) {
        AuthorWorksRequest req = new AuthorWorksRequest();
        req.setRequest_id(UUID.randomUUID().toString());
        req.setAuthor_id(authorId);
        req.setFrom_date(fromDate);
        req.setInclude_enrichment(true);
        req.setFormat("legacy");
        AuthorWorksRequest.Paging p = new AuthorWorksRequest.Paging();
        p.setPage_size(pageSize);
        p.setCursor(cursor);
        req.setPaging(p);
        return req;
    }

    private AuthorWorksResponse callPython(AuthorWorksRequest req) {
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            AuthorWorksResponse response = scopusPythonClient.post()
                    .uri("/v1/author-works")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(AuthorWorksResponse.class)
                    .onErrorResume(ex -> {
                        IntegrationException mapped = mapIntegrationException("authorWorks", ex);
                        log.error("Python service call failed: code={}, retryable={}, message={}",
                                mapped.getErrorCode(), mapped.isRetryable(), mapped.getMessage());
                        return Mono.error(mapped);
                    })
                    .block();
            if (response == null) {
                throw new IntegrationException(
                        IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                        false,
                        "Python author works service returned empty response"
                );
            }
            meterRegistry.counter("core.external.scopus_python.calls", "operation", "authorWorks", "outcome", "success")
                    .increment();
            timer.stop(meterRegistry.timer("core.external.scopus_python.duration", "operation", "authorWorks", "outcome", "success"));
            return response;
        } catch (RuntimeException ex) {
            meterRegistry.counter("core.external.scopus_python.calls", "operation", "authorWorks", "outcome", "failure")
                    .increment();
            timer.stop(meterRegistry.timer("core.external.scopus_python.duration", "operation", "authorWorks", "outcome", "failure"));
            throw ex;
        }
    }

    private boolean isReadyForAttempt(String nextAttemptAt) {
        if (nextAttemptAt == null || nextAttemptAt.isBlank()) {
            return true;
        }
        try {
            return !Instant.parse(nextAttemptAt).isAfter(Instant.now());
        } catch (Exception ex) {
            return true;
        }
    }

    private long computeBackoffSeconds(int attemptCount) {
        long exponent = Math.max(0, attemptCount - 1);
        long backoff = initialBackoffSeconds * (1L << Math.min(exponent, 10));
        return Math.min(backoff, maxBackoffSeconds);
    }

    private void handlePublicationTaskFailure(ScopusPublicationUpdate task, Exception exception) {
        IntegrationException mapped = mapRuntimeException(exception);
        int maxAttempts = task.getMaxAttempts() > 0 ? task.getMaxAttempts() : defaultMaxAttempts;
        if (mapped.isRetryable() && task.getAttemptCount() < maxAttempts) {
            long backoff = computeBackoffSeconds(task.getAttemptCount());
            task.setStatus(Status.PENDING);
            task.setNextAttemptAt(Instant.now().plusSeconds(backoff).toString());
            task.setMessage("RETRY_SCHEDULED: " + mapped.getMessage());
        } else {
            task.setStatus(Status.FAILED);
            task.setExecutionDate(LocalDate.now(Z).toString());
            task.setMessage("FAILED: " + mapped.getMessage());
        }
        task.setLastErrorCode(mapped.getErrorCode().name());
        task.setLastErrorMessage(mapped.getMessage());
        taskRepo.save(task);
        log.error("Publication task {} failed: code={}, retryable={}, attempt={}/{}",
                task.getId(), mapped.getErrorCode(), mapped.isRetryable(), task.getAttemptCount(), maxAttempts, mapped);
    }

    private void handleCitationTaskFailure(ScopusCitationsUpdate task, Exception exception) {
        IntegrationException mapped = mapRuntimeException(exception);
        int maxAttempts = task.getMaxAttempts() > 0 ? task.getMaxAttempts() : defaultMaxAttempts;
        if (mapped.isRetryable() && task.getAttemptCount() < maxAttempts) {
            long backoff = computeBackoffSeconds(task.getAttemptCount());
            task.setStatus(Status.PENDING);
            task.setNextAttemptAt(Instant.now().plusSeconds(backoff).toString());
            task.setMessage("RETRY_SCHEDULED: " + mapped.getMessage());
        } else {
            task.setStatus(Status.FAILED);
            task.setExecutionDate(LocalDate.now(Z).toString());
            task.setMessage("FAILED: " + mapped.getMessage());
        }
        task.setLastErrorCode(mapped.getErrorCode().name());
        task.setLastErrorMessage(mapped.getMessage());
        citationsTaskRepo.save(task);
        log.error("Citations task {} failed: code={}, retryable={}, attempt={}/{}",
                task.getId(), mapped.getErrorCode(), mapped.isRetryable(), task.getAttemptCount(), maxAttempts, mapped);
    }

    private IntegrationException mapRuntimeException(Throwable exception) {
        if (exception instanceof IntegrationException ie) {
            return ie;
        }
        return new IntegrationException(
                IntegrationErrorCode.PERSISTENCE_ERROR,
                false,
                exception.getMessage() == null ? "Unexpected failure" : exception.getMessage(),
                exception
        );
    }

    private IntegrationException mapIntegrationException(String operation, Throwable exception) {
        if (exception instanceof IntegrationException ie) {
            return ie;
        }
        WebClientResponseException responseException = findCause(exception, WebClientResponseException.class);
        if (responseException != null) {
            int status = responseException.getStatusCode().value();
            if (status >= 500) {
                return new IntegrationException(
                        IntegrationErrorCode.EXTERNAL_5XX,
                        true,
                        operation + " failed with HTTP " + status,
                        exception
                );
            }
            return new IntegrationException(
                    IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                    false,
                    operation + " failed with HTTP " + status,
                    exception
            );
        }
        if (findCause(exception, WebClientRequestException.class) != null) {
            return new IntegrationException(
                    IntegrationErrorCode.EXTERNAL_TIMEOUT,
                    true,
                    operation + " failed to reach external service",
                    exception
            );
        }
        if (findCause(exception, DataBufferLimitException.class) != null) {
            return new IntegrationException(
                    IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                    false,
                    operation + " failed because response payload exceeded configured buffer size",
                    exception
            );
        }
        if (findCause(exception, DecodingException.class) != null) {
            return new IntegrationException(
                    IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                    false,
                    operation + " failed because response payload could not be decoded",
                    exception
            );
        }
        Throwable rootCause = rootCause(exception);
        String details = rootCause.getMessage();
        String suffix = (details == null || details.isBlank()) ? "" : ": " + details;
        return new IntegrationException(
                IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                false,
                operation + " failed with unexpected integration error (" + rootCause.getClass().getSimpleName() + ")" + suffix,
                exception
        );
    }

    private Throwable rootCause(Throwable exception) {
        Throwable current = Exceptions.unwrap(exception);
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 20) {
            current = current.getCause();
        }
        return current;
    }

    private <T extends Throwable> T findCause(Throwable exception, Class<T> type) {
        Throwable current = Exceptions.unwrap(exception);
        int guard = 0;
        while (current != null && guard++ < 20) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
