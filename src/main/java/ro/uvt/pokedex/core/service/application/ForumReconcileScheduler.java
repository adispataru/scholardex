package ro.uvt.pokedex.core.service.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H66B Phase 3 — runs the Tier-1 forum {@link ForumReconcileService#reconcile} on a schedule (default nightly),
 * the cold-path partner of the Tier-2 incremental resolve. It collapses the transient duplicates that uploads
 * mint and re-onboards curated membership, so the registry converges without anyone clicking a button.
 *
 * <p>Runs unconditionally each tick (reconcile is idempotent — a no-op sweep on an already-consistent registry),
 * which avoids depending on a resettable backlog counter (the {@code unreconciled_mints} metric is monotonic
 * observability, not a gate). An in-flight guard prevents overlapping runs; {@code enabled=false} disables it.
 * The same operation is exposed for on-demand use via the admin {@code POST /forum/reconcile} endpoint.
 */
@Component
public class ForumReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ForumReconcileScheduler.class);

    private final ForumReconcileService forumReconcileService;
    private final boolean enabled;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public ForumReconcileScheduler(
            ForumReconcileService forumReconcileService,
            @Value("${core.h66b.forum-reconcile.enabled:true}") boolean enabled) {
        this.forumReconcileService = forumReconcileService;
        this.enabled = enabled;
    }

    /** Default 03:00 daily; override with {@code core.h66b.forum-reconcile.cron}. */
    @Scheduled(cron = "${core.h66b.forum-reconcile.cron:0 0 3 * * *}")
    public void runScheduledReconcile() {
        if (!enabled) {
            log.debug("Scheduled forum reconcile skipped: disabled");
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            log.warn("Scheduled forum reconcile skipped: a reconcile is already in flight");
            return;
        }
        try {
            forumReconcileService.reconcile("scheduled-nightly");
        } catch (RuntimeException e) {
            log.error("Scheduled forum reconcile failed", e);
        } finally {
            inFlight.set(false);
        }
    }
}
