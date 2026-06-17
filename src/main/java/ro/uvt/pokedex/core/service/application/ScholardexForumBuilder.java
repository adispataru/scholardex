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

    /** The four per-step results of a Scopus-side forum build, kept separate so callers can aggregate them. */
    public record ScopusForumBuildResult(
            ImportProcessingResult dedup,
            ImportProcessingResult canonicalization,
            ImportProcessingResult erihOnboarding,
            ImportProcessingResult erihDedup
    ) {
    }

    /**
     * Build/refresh the canonical forum registry from the Scopus-side forum facts, forums-first:
     * <ol>
     *   <li>dedup pre-existing canonical forums (ISSN + erihId clusters, safe-merge),</li>
     *   <li>canonicalize the stage-2 Scopus forum facts (Source List / CiteScore) into the registry,</li>
     *   <li>onboard ERIH ids onto matching forums,</li>
     *   <li>a conditional dedup to merge the erihId-shared split-journals ERIH just surfaced (C1 part 2).</li>
     * </ol>
     */
    public ScopusForumBuildResult buildScopusForums(String batchId, String correlationId) {
        ImportProcessingResult dedup = deduplicationService.deduplicateForums(batchId, correlationId);
        ImportProcessingResult canonicalization =
                wosScholardexOnboardingService.runScopusForumCanonicalization(batchId, correlationId);
        ImportProcessingResult erihOnboarding = erihOnboardingService.onboardErih();
        ImportProcessingResult erihDedup = erihOnboarding.getUpdatedCount() > 0
                ? deduplicationService.deduplicateForums(batchId, correlationId + "-erih")
                : new ImportProcessingResult(0);
        log.info("Forum build complete (correlationId={}): dedupMerged={} canonProcessed={} "
                        + "erihForumsUpdated={} erihDedupMerged={}",
                correlationId, dedup.getUpdatedCount(), canonicalization.getProcessedCount(),
                erihOnboarding.getUpdatedCount(), erihDedup.getUpdatedCount());
        return new ScopusForumBuildResult(dedup, canonicalization, erihOnboarding, erihDedup);
    }
}
