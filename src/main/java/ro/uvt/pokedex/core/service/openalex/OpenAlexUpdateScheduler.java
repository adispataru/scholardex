package ro.uvt.pokedex.core.service.openalex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.tasks.OpenAlexAuthorUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.repository.tasks.OpenAlexAuthorUpdateRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.OpenAlexCanonicalizationService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * H66B Phase 4a — polls {@link OpenAlexAuthorUpdate} tasks and runs the on-demand ORCID sync: fetch the
 * researcher's OpenAlex works, upsert source-facts, and canonicalize (link/mint). Mirrors the Scopus scheduler's
 * poll / claim / exponential-backoff shape with a leaner failure model (OpenAlex is a simple keyless API).
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "core.openalex.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OpenAlexUpdateScheduler {

    private static final ZoneId Z = ZoneId.systemDefault();

    private final OpenAlexAuthorUpdateRepository taskRepo;
    private final OpenAlexImportService importService;
    private final OpenAlexCanonicalizationService canonicalizationService;
    private final ro.uvt.pokedex.core.service.importing.scopus.OpenAlexCitationCanonicalizationService citationCanonicalizationService;
    private final ro.uvt.pokedex.core.service.dblp.DblpConferenceResolveService dblpConferenceResolveService;
    private final ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService projectionBuilderService;

    @Value("${openalex.update.max-attempts:3}")
    private int defaultMaxAttempts;
    @Value("${openalex.update.retry.initial-backoff-seconds:60}")
    private long initialBackoffSeconds;
    @Value("${openalex.update.retry.max-backoff-seconds:3600}")
    private long maxBackoffSeconds;

    private final java.util.concurrent.atomic.AtomicBoolean polling =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * On-demand kick right after a task is created, so the sync starts within seconds instead of
     * waiting for the next scheduled tick. Async so the creating HTTP request doesn't block; the
     * {@code polling} guard makes an overlap with the scheduled poll a cheap no-op.
     */
    @org.springframework.scheduling.annotation.Async
    public void triggerImmediatePoll() {
        pollQueue();
    }

    @Scheduled(fixedDelayString = "${openalex.update.poll-ms:60000}")
    public void pollQueue() {
        if (!polling.compareAndSet(false, true)) {
            log.debug("OpenAlex scheduler poll skipped — another poll is already running");
            return;
        }
        try {
            List<OpenAlexAuthorUpdate> tasks = taskRepo.findByStatusOrderByInitiatedDate(Status.PENDING);
            if (tasks.isEmpty()) {
                return;
            }
            int processed = 0;
            Instant now = Instant.now();
            for (OpenAlexAuthorUpdate task : tasks) {
                if (!isReadyForAttempt(task.getNextAttemptAt(), now)) {
                    continue;
                }
                try {
                    runOne(task);
                    processed++;
                } catch (Exception e) {
                    handleFailure(task, e);
                }
            }
            if (processed > 0) {
                log.info("OpenAlex scheduler poll completed: tasksProcessed={}", processed);
            }
        } finally {
            polling.set(false);
        }
    }

    private void runOne(OpenAlexAuthorUpdate task) {
        task.setAttemptCount(task.getAttemptCount() + 1);
        if (task.getMaxAttempts() <= 0) {
            task.setMaxAttempts(defaultMaxAttempts);
        }
        task.setStatus(Status.IN_PROGRESS);
        task.setMessage("Starting OpenAlex author sync");
        task.setNextAttemptAt(null);
        taskRepo.save(task);

        String batchId = "scheduler-openalex-task-" + task.getId() + "-attempt-" + task.getAttemptCount();
        List<String> workIds = importService.importByOrcid(
                task.getOrcid(), task.getResearcherAuthorId(), batchId, batchId);
        ImportProcessingResult result = canonicalizationService.resolve(workIds);
        // Build DOI-keyed citation edges for the synced works (resolves referenced-work DOIs against the corpus).
        ImportProcessingResult citationResult = citationCanonicalizationService.resolve(workIds);
        // Phase 4b: resolve hidden-conference venues for the synced works via the DBLP API (conf/X forum + forumId).
        ImportProcessingResult dblpResult = dblpConferenceResolveService.resolveByOpenAlexWorkIds(workIds);
        // Refresh read-projections so the synced works are visible in the researcher's workspace.
        projectionBuilderService.rebuildViews();

        task.setStatus(Status.COMPLETED);
        task.setMessage("Synced " + workIds.size() + " works (imported=" + result.getImportedCount()
                + ", linked=" + result.getUpdatedCount() + ", skipped=" + result.getSkippedCount()
                + ", citationEdges=" + citationResult.getImportedCount()
                + ", dblpConferences=" + dblpResult.getImportedCount() + ")");
        task.setExecutionDate(LocalDate.now(Z).toString());
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);
        task.setNextAttemptAt(null);
        taskRepo.save(task);
        log.info("OpenAlex author task {} completed: orcid={} works={} imported={} linked={} skipped={}",
                task.getId(), task.getOrcid(), workIds.size(),
                result.getImportedCount(), result.getUpdatedCount(), result.getSkippedCount());
    }

    private void handleFailure(OpenAlexAuthorUpdate task, Exception exception) {
        int maxAttempts = task.getMaxAttempts() > 0 ? task.getMaxAttempts() : defaultMaxAttempts;
        String message = exception.getMessage();
        if (task.getAttemptCount() < maxAttempts) {
            long backoff = computeBackoffSeconds(task.getAttemptCount());
            task.setStatus(Status.PENDING);
            task.setNextAttemptAt(Instant.now().plusSeconds(backoff).toString());
            task.setMessage("RETRY_SCHEDULED: " + message);
        } else {
            task.setStatus(Status.FAILED);
            task.setNextAttemptAt(null);
            task.setMessage("FAILED: " + message);
        }
        task.setLastErrorCode(exception.getClass().getSimpleName());
        task.setLastErrorMessage(message);
        taskRepo.save(task);
        log.warn("OpenAlex author task {} failed (attempt {}/{}): {}",
                task.getId(), task.getAttemptCount(), maxAttempts, message);
    }

    private long computeBackoffSeconds(int attemptCount) {
        long exponent = Math.max(0, attemptCount - 1);
        long backoff = initialBackoffSeconds * (1L << Math.min(exponent, 10));
        return Math.min(backoff, maxBackoffSeconds);
    }

    private boolean isReadyForAttempt(String nextAttemptAt, Instant now) {
        if (nextAttemptAt == null || nextAttemptAt.isBlank()) {
            return true;
        }
        try {
            return !Instant.parse(nextAttemptAt).isAfter(now);
        } catch (Exception ex) {
            return true;
        }
    }
}
