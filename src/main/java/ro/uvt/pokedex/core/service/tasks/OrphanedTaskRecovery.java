package ro.uvt.pokedex.core.service.tasks;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.model.tasks.Task;
import ro.uvt.pokedex.core.repository.tasks.OpenAlexAuthorUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;

import java.util.List;
import java.util.function.Supplier;

/**
 * Re-queues sync tasks a previous process left {@code IN_PROGRESS}.
 *
 * <p>The schedulers only ever pick up {@code PENDING} tasks, so a task that was running when the application
 * stopped (a deploy, a crash, an OOM kill) stayed {@code IN_PROGRESS} forever — to the researcher it looked like
 * a sync that never started (prod 2026-08-31…09-02: three OpenAlex tasks hung on a DBLP lookup and were orphaned
 * by the next deploy).
 *
 * <p>Runs in {@code @PostConstruct}: scheduled polls only start once the context is refreshed, so at this point
 * no task can have been started by THIS process, and the core deployment uses the {@code Recreate} strategy, so
 * no other process is alive either — every {@code IN_PROGRESS} task is an orphan. A task that has already used
 * all its attempts is closed as {@code FAILED} instead, so a run that kills the JVM cannot restart forever.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "core.tasks.orphan-recovery.enabled", havingValue = "true", matchIfMissing = true)
public class OrphanedTaskRecovery {

    static final String ERROR_CODE = "ORPHANED_BY_RESTART";

    private final OpenAlexAuthorUpdateRepository openAlexTasks;
    private final ScopusPublicationUpdateRepository scopusPublicationTasks;
    private final ScopusCitationUpdateRepository scopusCitationTasks;
    private final int defaultMaxAttempts;

    public OrphanedTaskRecovery(OpenAlexAuthorUpdateRepository openAlexTasks,
                                ScopusPublicationUpdateRepository scopusPublicationTasks,
                                ScopusCitationUpdateRepository scopusCitationTasks,
                                @Value("${core.tasks.orphan-recovery.default-max-attempts:3}") int defaultMaxAttempts) {
        this.openAlexTasks = openAlexTasks;
        this.scopusPublicationTasks = scopusPublicationTasks;
        this.scopusCitationTasks = scopusCitationTasks;
        this.defaultMaxAttempts = Math.max(1, defaultMaxAttempts);
    }

    @PostConstruct
    void recoverOnStartup() {
        try {
            recoverAll();
        } catch (RuntimeException e) {
            // Never block startup on this: the tasks simply stay as they were until the next restart.
            log.warn("Orphaned sync-task recovery skipped: {}", e.toString());
        }
    }

    /** @return how many tasks were touched (re-queued or closed) */
    int recoverAll() {
        int touched = 0;
        touched += recover("OpenAlex author sync", openAlexTasks,
                () -> openAlexTasks.findByStatusOrderByInitiatedDate(Status.IN_PROGRESS));
        touched += recover("Scopus publications sync", scopusPublicationTasks,
                () -> scopusPublicationTasks.findByStatusOrderByInitiatedDate(Status.IN_PROGRESS));
        touched += recover("Scopus citations sync", scopusCitationTasks,
                () -> scopusCitationTasks.findByStatusOrderByInitiatedDate(Status.IN_PROGRESS));
        return touched;
    }

    private <T extends Task> int recover(String label, MongoRepository<T, String> repo, Supplier<List<T>> orphans) {
        List<T> tasks = orphans.get();
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        int requeued = 0;
        int closed = 0;
        for (T task : tasks) {
            int maxAttempts = task.getMaxAttempts() > 0 ? task.getMaxAttempts() : defaultMaxAttempts;
            task.setNextAttemptAt(null);
            task.setLastErrorCode(ERROR_CODE);
            if (task.getAttemptCount() >= maxAttempts) {
                task.setStatus(Status.FAILED);
                task.setLastErrorMessage("Interrupted by an application restart on attempt "
                        + task.getAttemptCount() + " of " + maxAttempts + "; no attempts left");
                task.setMessage("FAILED: interrupted by an application restart; start the sync again");
                closed++;
            } else {
                task.setStatus(Status.PENDING);
                task.setLastErrorMessage("Interrupted by an application restart on attempt "
                        + task.getAttemptCount() + " of " + maxAttempts);
                task.setMessage("Re-queued after an application restart interrupted the run");
                requeued++;
            }
            repo.save(task);
        }
        log.warn("{}: {} orphaned IN_PROGRESS task(s) found at startup — re-queued={} closedAsFailed={}",
                label, tasks.size(), requeued, closed);
        return tasks.size();
    }
}
