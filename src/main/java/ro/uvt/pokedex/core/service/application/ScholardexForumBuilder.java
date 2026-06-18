package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

/**
 * H66B B66B.1 — the single entry point for building the canonical forum registry from the already-parsed
 * source facts, in **forums-first** order. Today it consolidates the Scopus-side forum build that was
 * scattered inline across {@code ScopusBigBangMigrationService}'s runFull / buildFacts / incremental paths
 * (dedup → Scopus forum canonicalization → ERIH erihIds onboarding → erih-dedup).
 *
 * <p>Facade step (strangler): it still delegates to the existing services; later moves internalize the
 * logic and remove the publication-path forum derivation, and fold WoS journal onboarding (which still runs
 * in the WoS rebuild) in here so this is the one place forums are built from every source. See
 * [[h66b-entity-oriented-builders]].
 */
@Service
@RequiredArgsConstructor
public class ScholardexForumBuilder {

    private static final Logger log = LoggerFactory.getLogger(ScholardexForumBuilder.class);

    private final ScholardexForumDeduplicationService deduplicationService;
    private final WosScholardexOnboardingService wosScholardexOnboardingService;
    private final ErihOnboardingService erihOnboardingService;
    private final DoajOnboardingService doajOnboardingService;

    /** The per-step results of a forum build, kept separate so callers can aggregate them. */
    public record ScopusForumBuildResult(
            ImportProcessingResult dedup,
            ImportProcessingResult canonicalization,
            ImportProcessingResult erihOnboarding,
            ImportProcessingResult doajOnboarding,
            ImportProcessingResult wosOnboarding,
            ImportProcessingResult membershipDedup
    ) {
    }

    /**
     * Build/refresh the canonical forum registry, identity-first and in authority order:
     * <ol>
     *   <li>dedup pre-existing canonical forums (ISSN + erihId clusters, safe-merge),</li>
     *   <li>canonicalize the stage-2 Scopus forum facts (Source List / CiteScore / observed venues),</li>
     *   <li>ERIH then DOAJ as create-or-match identity sources (tag matches / mint source-only venues),</li>
     *   <li><b>WoS create-or-match LAST</b> — identity-of-last-resort, so the curated lists win identity and
     *       WoS folds in (the M4-A display-name rule still makes the WoS title win the display name),</li>
     *   <li>a conditional dedup to merge the shared-id split-journals the create-or-match sources surfaced.</li>
     * </ol>
     * Requires {@code wos.journal_identity} to already be built (the WoS fact phase) — no-ops if absent.
     */
    public ScopusForumBuildResult buildScopusForums(String batchId, String correlationId) {
        ImportProcessingResult dedup = deduplicationService.deduplicateForums(batchId, correlationId);
        ImportProcessingResult canonicalization =
                wosScholardexOnboardingService.runScopusForumCanonicalization(batchId, correlationId);
        ImportProcessingResult erihOnboarding = erihOnboardingService.onboardErih();
        ImportProcessingResult doajOnboarding = doajOnboardingService.onboardDoaj();
        ImportProcessingResult wosOnboarding =
                wosScholardexOnboardingService.runWosForumOnboarding(batchId, correlationId);
        // Re-dedup when any create-or-match source touched the registry (shared-id clusters worth merging).
        boolean registryChanged = erihOnboarding.getUpdatedCount() > 0 || doajOnboarding.getUpdatedCount() > 0
                || wosOnboarding.getUpdatedCount() > 0 || wosOnboarding.getImportedCount() > 0;
        ImportProcessingResult membershipDedup = registryChanged
                ? deduplicationService.deduplicateForums(batchId, correlationId + "-membership")
                : new ImportProcessingResult(0);
        log.info("Forum build complete (correlationId={}): dedupMerged={} canonProcessed={} "
                        + "erihForumsUpdated={} doajForumsUpdated={} wosForumsImported={} wosForumsUpdated={} membershipDedupMerged={}",
                correlationId, dedup.getUpdatedCount(), canonicalization.getProcessedCount(),
                erihOnboarding.getUpdatedCount(), doajOnboarding.getUpdatedCount(),
                wosOnboarding.getImportedCount(), wosOnboarding.getUpdatedCount(), membershipDedup.getUpdatedCount());
        return new ScopusForumBuildResult(dedup, canonicalization, erihOnboarding, doajOnboarding, wosOnboarding, membershipDedup);
    }
}
