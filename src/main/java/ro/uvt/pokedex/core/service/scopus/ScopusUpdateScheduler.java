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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
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
import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(value = "core.scopus.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ScopusUpdateScheduler {

    private final ScopusPublicationUpdateRepository taskRepo;
    private final ScopusCitationUpdateRepository citationsTaskRepo;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ScopusImportEventIngestionService importEventIngestionService;
    private final ScopusCanonicalMaterializationService canonicalMaterializationService;
    // H82: FULL-sync narrow re-enrichment of existing pubs + the dirty-projection push that follows it.
    private final ro.uvt.pokedex.core.service.importing.scopus.ScopusExistingPublicationReenrichmentService reenrichmentService;
    private final ro.uvt.pokedex.core.service.application.ScholardexProjectionDirtyService projectionDirtyService;
    private final MeterRegistry meterRegistry;

    private final WebClient scopusPythonClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScopusSchedulerRetryPolicy retryPolicy = new ScopusSchedulerRetryPolicy();
    private final ScopusIntegrationExceptionMapper exceptionMapper = new ScopusIntegrationExceptionMapper();
    private final ScopusPublicationSyncPlanner publicationPlanner = new ScopusPublicationSyncPlanner();
    private final ScopusCitationSyncPlanner citationPlanner = new ScopusCitationSyncPlanner(publicationPlanner);


    @Value("${scopus.update.page-size:100}")
    private int pageSize;
    @Value("${scopus.update.max-attempts:3}")
    private int defaultMaxAttempts;
    @Value("${scopus.update.retry.initial-backoff-seconds:60}")
    private long initialBackoffSeconds;
    @Value("${scopus.update.retry.max-backoff-seconds:3600}")
    private long maxBackoffSeconds;




    /**
     * Ensures the scheduled poll and an on-demand {@link #triggerImmediatePoll()} never run
     * concurrently — two overlapping polls could both claim the same PENDING task before either
     * flips it to IN_PROGRESS. The loser simply skips; the winner drains the whole queue anyway.
     */
    private final java.util.concurrent.atomic.AtomicBoolean polling =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Fired right after a user submits a sync so it starts within seconds instead of waiting up to a
     * full poll interval. Async so the submit request returns immediately; a no-op if a poll is
     * already in flight (that run picks up the freshly-queued task).
     */
    @org.springframework.scheduling.annotation.Async
    public void triggerImmediatePoll() {
        pollQueue();
    }

    @Scheduled(fixedDelayString = "${scopus.update.poll-ms:60000}")
    public void pollQueue() {
        if (!polling.compareAndSet(false, true)) {
            log.debug("Scopus scheduler poll skipped — another poll is already running");
            return;
        }
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
            polling.set(false);
        }
    }

    private int processPublicationTasks() {
        List<ScopusPublicationUpdate> tasks =
                taskRepo.findByStatusOrderByInitiatedDate(Status.PENDING);

        if (tasks.isEmpty()) return 0;

        int processedTasks = 0;

        for (ScopusPublicationUpdate t : tasks) {
            if (!retryPolicy.isReadyForAttempt(t.getNextAttemptAt(), Instant.now())) {
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
        List<ScholardexPublicationView> authorPublications =
                scholardexProjectionReadService.findAllPublicationsByAuthorsContaining(authorScopusId);
        String fromDate = publicationPlanner.resolveFromDate(task, authorPublications);

        String cursor = null;
        int imported = 0;
        String batchId = "scheduler-publication-task-" + task.getId() + "-attempt-" + task.getAttemptCount();
        // H82: on a FULL sync (no fromDate window), every eid the API returns is collected so existing
        // pubs — which payload-hash dedupe keeps out of the ingest pipeline — still get the narrow
        // precedence-field re-enrichment pass after the loop.
        boolean fullSync = fromDate == null;
        java.util.Set<String> seenEids = fullSync ? new java.util.LinkedHashSet<>() : null;

        do {
            AuthorWorksRequest req = publicationPlanner.buildRequest(authorScopusId, fromDate, cursor, pageSize);
            AuthorWorksResponse resp = callPython(req);

            if (resp.getItems() != null) {
                for (Map<String, Object> item : resp.getItems()) {
                    String sourceRecordId = text(item, "eid");
                    if (sourceRecordId == null || sourceRecordId.isBlank()) {
                        continue;
                    }
                    if (seenEids != null) {
                        seenEids.add(sourceRecordId);
                    }
                    // PERIOD mode: skip items whose coverDate year is beyond the requested end year
                    if ("PERIOD".equals(task.getSyncMode()) && task.getEndYear() != null) {
                        String coverDate = text(item, "coverDate");
                        if (coverDate != null && coverDate.length() >= 4) {
                            try {
                                int itemYear = Integer.parseInt(coverDate.substring(0, 4));
                                if (itemYear > task.getEndYear()) continue;
                            } catch (NumberFormatException ignored) { /* keep the item */ }
                        }
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

        // H82: re-claim Scopus precedence fields (coverDate/coverDisplayDate) on this author's EXISTING
        // pubs — payload-hash dedupe keeps unchanged records out of the ingest pipeline, so without this
        // pass a FULL sync is a no-op for them and merge-rule fixes stay stranded until a full rebuild.
        int reenriched = 0;
        if (seenEids != null && !seenEids.isEmpty()) {
            reenriched = reenrichmentService.reclaimPrecedenceFields(
                    seenEids, "H82 full-sync re-enrichment, task " + task.getId());
        }

        MDC.put("phase", "complete");
        task.setStatus(Status.COMPLETED);
        task.setMessage("Imported " + imported + " items" + (fromDate != null ? " since " + fromDate : " (full update)")
                + (reenriched > 0 ? ", re-enriched " + reenriched + " existing" : ""));
        task.setExecutionDate(Instant.now().toString());
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);
        task.setNextAttemptAt(null);
        taskRepo.save(task);
        canonicalMaterializationService.rebuildFactsAndViews("scheduler-publication-task-" + task.getId(), batchId);
        if (reenriched > 0) {
            // Push the re-claimed fields into the Postgres read model now (per-batch partial path via the
            // pubs' own sourceBatchIds) — otherwise the researcher's next report refresh reads stale dates
            // until the nightly reconcile or an admin dirty-rebuild.
            var rebuild = projectionDirtyService.rebuildDirtyProjections();
            log.info("H82 dirty-projection rebuild after re-enrichment: requested={}, rebuilt={}, failed={}",
                    rebuild.requestedMarkers(), rebuild.rebuiltMarkers(), rebuild.failedMarkers());
        }
    }



    private int processCitationTasks() {
        List<ScopusCitationsUpdate> tasks =
                citationsTaskRepo.findByStatusOrderByInitiatedDate(Status.PENDING);

        if (tasks.isEmpty()) return 0;

        int processedTasks = 0;

        for (ScopusCitationsUpdate t : tasks) {
            if (!retryPolicy.isReadyForAttempt(t.getNextAttemptAt(), Instant.now())) {
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

        Map<String, String> eidLastDate = computeEidLastCitationDatesForTask(task, authorScopusId);
        if (eidLastDate.isEmpty()) {
            task.setStatus(Status.COMPLETED);
            task.setMessage("No publications found for author " + authorScopusId + ", nothing to update.");
            task.setExecutionDate(Instant.now().toString());
            task.setLastErrorCode(null);
            task.setLastErrorMessage(null);
            task.setNextAttemptAt(null);
            citationsTaskRepo.save(task);
            return;
        }

        CitationsByEidRequest req = citationPlanner.buildRequest(eidLastDate);

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
        task.setExecutionDate(Instant.now().toString());
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

    private Map<String, String> computeEidLastCitationDatesForTask(ScopusCitationsUpdate task, String authorScopusId) {
        List<ScholardexPublicationView> authorPublications =
                scholardexProjectionReadService.findAllPublicationsByAuthorsContaining(authorScopusId);
        if (authorPublications.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> citedIds = citationPlanner.citedPublicationIds(authorPublications);
        List<ScholardexCitationView> citations = scholardexProjectionReadService.findAllCitationsByCitedIdIn(citedIds);
        List<String> citingIds = citationPlanner.citingPublicationIds(citations);
        List<ScholardexPublicationView> citingPublications = citingIds.isEmpty()
                ? Collections.emptyList()
                : scholardexProjectionReadService.findAllPublicationsByIdIn(citingIds);

        Map<String, String> computedDates = citationPlanner.computeEidLastCitationDates(
                authorPublications,
                citations,
                citingPublications
        );
        return citationPlanner.resolveEidLastDates(task, computedDates);
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
                        IntegrationException mapped = exceptionMapper.mapIntegrationException("citationsByEid", ex);
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

    private AuthorWorksResponse callPython(AuthorWorksRequest req) {
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            AuthorWorksResponse response = scopusPythonClient.post()
                    .uri("/v1/author-works")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(AuthorWorksResponse.class)
                    .onErrorResume(ex -> {
                        IntegrationException mapped = exceptionMapper.mapIntegrationException("authorWorks", ex);
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

    private void handlePublicationTaskFailure(ScopusPublicationUpdate task, Exception exception) {
        IntegrationException mapped = exceptionMapper.mapRuntimeException(exception);
        ScopusSchedulerRetryPolicy.FailureDecision decision = retryPolicy.decideFailure(
                mapped,
                task.getAttemptCount(),
                task.getMaxAttempts(),
                defaultMaxAttempts,
                initialBackoffSeconds,
                maxBackoffSeconds,
                Instant.now()
        );
        task.setStatus(decision.status());
        task.setMessage(decision.message());
        task.setNextAttemptAt(decision.nextAttemptAt());
        if (decision.terminal()) {
            task.setExecutionDate(Instant.now().toString());
        }
        task.setLastErrorCode(decision.lastErrorCode());
        task.setLastErrorMessage(decision.lastErrorMessage());
        taskRepo.save(task);
        log.error("Publication task {} failed: code={}, retryable={}, attempt={}/{}",
                task.getId(), mapped.getErrorCode(), mapped.isRetryable(), task.getAttemptCount(), decision.maxAttempts(), mapped);
    }

    private void handleCitationTaskFailure(ScopusCitationsUpdate task, Exception exception) {
        IntegrationException mapped = exceptionMapper.mapRuntimeException(exception);
        ScopusSchedulerRetryPolicy.FailureDecision decision = retryPolicy.decideFailure(
                mapped,
                task.getAttemptCount(),
                task.getMaxAttempts(),
                defaultMaxAttempts,
                initialBackoffSeconds,
                maxBackoffSeconds,
                Instant.now()
        );
        task.setStatus(decision.status());
        task.setMessage(decision.message());
        task.setNextAttemptAt(decision.nextAttemptAt());
        if (decision.terminal()) {
            task.setExecutionDate(Instant.now().toString());
        }
        task.setLastErrorCode(decision.lastErrorCode());
        task.setLastErrorMessage(decision.lastErrorMessage());
        citationsTaskRepo.save(task);
        log.error("Citations task {} failed: code={}, retryable={}, attempt={}/{}",
                task.getId(), mapped.getErrorCode(), mapped.isRetryable(), task.getAttemptCount(), decision.maxAttempts(), mapped);
    }

}
