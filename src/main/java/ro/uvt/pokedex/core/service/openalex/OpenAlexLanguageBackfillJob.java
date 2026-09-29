package ro.uvt.pokedex.core.service.openalex;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the language backfill to the end in the background: pass after pass until a pass finds nothing left.
 * The works of a whole university are tens of thousands, far more than one request should wait for, so the
 * admin starts the job and reads its state. One job at a time; starting it while it runs changes nothing.
 *
 * <p>The state lives in memory. A restart of the application ends a running job; starting it again picks up
 * where the data stands, because a work that got its language is no longer a candidate.</p>
 */
@Slf4j
@Service
public class OpenAlexLanguageBackfillJob {

    public enum State { IDLE, RUNNING, DONE, STOPPED_AT_LIMIT, FAILED }

    /** What the job has done so far. {@code error} is set only when it failed. */
    public record Status(State state, int passes, int works, int withLanguage, int withoutLanguage,
                         int venuesAsked, int venuesWithCountry, Instant startedAt, Instant finishedAt,
                         String error) {
        static Status idle() {
            return new Status(State.IDLE, 0, 0, 0, 0, 0, 0, null, null, null);
        }

        Status after(OpenAlexLanguageBackfillService.Result pass) {
            return new Status(State.RUNNING, passes + 1, works + pass.candidates(),
                    withLanguage + pass.withLanguage(), withoutLanguage + pass.withoutLanguage(),
                    venuesAsked + pass.venuesAsked(), venuesWithCountry + pass.venuesWithCountry(),
                    startedAt, null, null);
        }

        Status ended(State with, String failure) {
            return new Status(with, passes, works, withLanguage, withoutLanguage, venuesAsked, venuesWithCountry,
                    startedAt, Instant.now(), failure);
        }
    }

    private final OpenAlexLanguageBackfillService service;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.idle());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openalex-language-backfill");
        thread.setDaemon(true);
        return thread;
    });

    public OpenAlexLanguageBackfillJob(OpenAlexLanguageBackfillService service) {
        this.service = service;
    }

    public Status status() {
        return status.get();
    }

    /**
     * @param worksPerPass works asked about in one pass
     * @param maxPasses    the job stops after this many passes even when works are left
     * @return true when the job was started, false when one is already running
     */
    public synchronized boolean start(int worksPerPass, int maxPasses) {
        if (status.get().state() == State.RUNNING) {
            return false;
        }
        int passes = Math.max(1, Math.min(1000, maxPasses));
        status.set(new Status(State.RUNNING, 0, 0, 0, 0, 0, 0, Instant.now(), null, null));
        executor.submit(() -> run(worksPerPass, passes));
        return true;
    }

    void run(int worksPerPass, int maxPasses) {
        try {
            for (int pass = 0; pass < maxPasses; pass++) {
                OpenAlexLanguageBackfillService.Result result = service.backfill(worksPerPass);
                if (result.candidates() == 0) {
                    status.set(status.get().ended(State.DONE, null));
                    log.info("OpenAlex language backfill job done: {}", status.get());
                    return;
                }
                status.set(status.get().after(result));
            }
            status.set(status.get().ended(State.STOPPED_AT_LIMIT, null));
            log.info("OpenAlex language backfill job stopped at its pass limit: {}", status.get());
        } catch (RuntimeException e) {
            status.set(status.get().ended(State.FAILED, e.toString()));
            log.warn("OpenAlex language backfill job failed after {} passes: {}", status.get().passes(), e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
