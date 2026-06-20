package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.CanonicalObservabilityMetrics;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService;

/**
 * H66B Phase 3 — <b>Tier 1 (reconcile)</b>: the single named entry point that makes the canonical forum
 * registry globally consistent and refreshes the reporting projections. It is the cold-path counterpart of the
 * Tier-2 {@link ScholardexForumBuilder#resolve} hot path: incremental uploads find-or-mint venues cheaply and
 * defer the global passes; this reconcile runs them — the full {@code buildScopusForums} (dedup → Scopus canon →
 * ERIH/DOAJ onboarding → WoS onboarding → membership dedup → M10 relink), which merges and re-points any
 * transient duplicates the resolves minted, followed by a projection rebuild so the merges/re-points become
 * visible in reporting.
 *
 * <p>One implementation, invoked by both the admin manual trigger ({@code POST /forum/reconcile}) and the nightly
 * scheduler — never two slightly-different code paths. Idempotent: on an already-consistent registry the dedup
 * finds nothing and it is a (cheap) no-op sweep + projection refresh.
 */
@Service
@RequiredArgsConstructor
public class ForumReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ForumReconcileService.class);
    // Stable provenance label for the reconcile's forum source-link writes (mirrors the full-build canon label).
    private static final String RECONCILE_BATCH = "scopus-forum-canonicalization";

    private final ScholardexForumBuilder forumBuilder;
    private final AuthorReconcileService authorReconcileService;
    private final ScholardexProjectionBuilderService projectionBuilderService;

    public ForumReconcileResult reconcile(String reason) {
        long startedAtNanos = System.nanoTime();
        log.info("Forum reconcile started: reason={}", reason);

        ScholardexForumBuilder.ScopusForumBuildResult forumBuild =
                forumBuilder.buildScopusForums(RECONCILE_BATCH, "reconcile-" + reason);
        // Author reconcile rides the same Tier-1 cadence, before the projection rebuild so the merges/re-points are
        // reflected in reporting. ORCID pass merges (idempotent); the fuzzy pass is dry-run unless flagged on.
        ImportProcessingResult authorOrcid = authorReconcileService.reconcileByOrcid(RECONCILE_BATCH, "reconcile-" + reason);
        ImportProcessingResult authorFuzzy = authorReconcileService.reconcileByName(RECONCILE_BATCH, "reconcile-" + reason);
        // H72 slice 2: over-split merge (same name + verified affiliation + ≥1 co-author). Dry-run unless
        // core.author-reconcile.affiliation-apply=true, so it is a safe candidate-emitter until enabled.
        ImportProcessingResult authorOverSplit =
                authorReconcileService.reconcileByNameAndAffiliation(RECONCILE_BATCH, "reconcile-" + reason);
        log.info("Author reconcile within forum reconcile: orcidMerged={} orcidQuarantined={} fuzzyMerged={} "
                        + "fuzzyReported={} overSplitMerged={} overSplitCandidates={}",
                authorOrcid.getImportedCount(), authorOrcid.getSkippedCount(),
                authorFuzzy.getImportedCount(), authorFuzzy.getSkippedCount(),
                authorOverSplit.getImportedCount(), authorOverSplit.getSkippedCount());
        ImportProcessingResult projection = projectionBuilderService.rebuildViews();

        long durationNanos = System.nanoTime() - startedAtNanos;
        String outcome = projection.getErrorCount() == 0 ? "success" : "failure";
        CanonicalObservabilityMetrics.recordReconcileRun("forum", outcome, durationNanos);
        log.info("Forum reconcile complete: reason={} outcome={} membershipDedupMerged={} wosRelinked={} "
                        + "projectionProcessed={} projectionErrors={} totalMs={}",
                reason, outcome,
                forumBuild.membershipDedup().getUpdatedCount(),
                forumBuild.wosRelink().getUpdatedCount() + forumBuild.wosRelink().getImportedCount(),
                projection.getProcessedCount(), projection.getErrorCount(),
                durationNanos / 1_000_000L);
        return new ForumReconcileResult(forumBuild, projection);
    }

    public record ForumReconcileResult(
            ScholardexForumBuilder.ScopusForumBuildResult forumBuild,
            ImportProcessingResult projection
    ) {
    }
}
