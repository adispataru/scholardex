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

    /** The per-step results of a Scopus-side forum build, kept separate so callers can aggregate them. */
    public record ScopusForumBuildResult(
            ImportProcessingResult dedup,
            ImportProcessingResult canonicalization,
            ImportProcessingResult erihOnboarding,
            ImportProcessingResult doajOnboarding,
            ImportProcessingResult membershipDedup
    ) {
    }

    /**
     * Build/refresh the canonical forum registry from the Scopus-side forum facts, forums-first:
     * <ol>
     *   <li>dedup pre-existing canonical forums (ISSN + erihId clusters, safe-merge),</li>
     *   <li>canonicalize the stage-2 Scopus forum facts (Source List / CiteScore) into the registry,</li>
     *   <li>onboard ERIH then DOAJ as create-or-match identity sources (tag matches / mint source-only venues),</li>
     *   <li>a conditional dedup to merge the shared-id split-journals ERIH/DOAJ just surfaced (C1 part 2).</li>
     * </ol>
     */
    public ScopusForumBuildResult buildScopusForums(String batchId, String correlationId) {
        ImportProcessingResult dedup = deduplicationService.deduplicateForums(batchId, correlationId);
        ImportProcessingResult canonicalization =
                wosScholardexOnboardingService.runScopusForumCanonicalization(batchId, correlationId);
        ImportProcessingResult erihOnboarding = erihOnboardingService.onboardErih();
        ImportProcessingResult doajOnboarding = doajOnboardingService.onboardDoaj();
        // Re-dedup only when a create-or-match source tagged existing forums (shared-id clusters worth
        // merging); source-only creates don't share ISSNs with existing forums, so they don't trigger it.
        boolean membershipTagged = erihOnboarding.getUpdatedCount() > 0 || doajOnboarding.getUpdatedCount() > 0;
        ImportProcessingResult membershipDedup = membershipTagged
                ? deduplicationService.deduplicateForums(batchId, correlationId + "-membership")
                : new ImportProcessingResult(0);
        log.info("Forum build complete (correlationId={}): dedupMerged={} canonProcessed={} "
                        + "erihForumsUpdated={} doajForumsUpdated={} membershipDedupMerged={}",
                correlationId, dedup.getUpdatedCount(), canonicalization.getProcessedCount(),
                erihOnboarding.getUpdatedCount(), doajOnboarding.getUpdatedCount(), membershipDedup.getUpdatedCount());
        return new ScopusForumBuildResult(dedup, canonicalization, erihOnboarding, doajOnboarding, membershipDedup);
    }
}
