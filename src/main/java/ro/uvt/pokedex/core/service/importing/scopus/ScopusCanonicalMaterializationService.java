package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.CanonicalObservabilityMetrics;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeReconciliationService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

@Service
@RequiredArgsConstructor
public class ScopusCanonicalMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(ScopusCanonicalMaterializationService.class);
    private static final String SOURCE_CANONICAL_MIXED = "CANONICAL_MIXED";

    private final ScopusFactBuilderService factBuilderService;
    private final UserDefinedFactBuilderService userDefinedFactBuilderService;
    private final ScholardexAffiliationCanonicalizationService affiliationCanonicalizationService;
    private final ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    private final ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    private final UserDefinedCanonicalizationService userDefinedCanonicalizationService;
    private final OpenAlexCanonicalizationService openAlexCanonicalizationService;
    private final ScholardexCitationCanonicalizationService citationCanonicalizationService;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexEdgeReconciliationService edgeReconciliationService;
    private final ScholardexProjectionBuilderService projectionBuilderService;

    public void rebuildFactsAndViews(String trigger) {
        rebuildFactsAndViews(trigger, null, CanonicalBuildOptions.defaults());
    }

    public void rebuildFactsAndViews(String trigger, String batchId) {
        rebuildFactsAndViews(trigger, batchId, CanonicalBuildOptions.defaults());
    }

    public void rebuildFactsAndViews(String trigger, String batchId, CanonicalBuildOptions canonicalOptions) {
        String runId = java.util.UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();
        boolean incrementalBatchRun = isIncrementalBatchRun(batchId, canonicalOptions);
        CanonicalBuildInputs buildInputs = incrementalBatchRun
                ? runIncrementalBatchMaintenance(batchId, canonicalOptions)
                : runFullMaintenance(canonicalOptions);
        ImportProcessingResult factResult = buildInputs.factResult();
        ImportProcessingResult userDefinedFactResult = buildInputs.userDefinedFactResult();
        CanonicalBuildOptions effectiveOptions = buildInputs.effectiveOptions();
        ImportProcessingResult canonicalAffiliationResult = buildInputs.canonicalAffiliationResult();
        ImportProcessingResult canonicalAuthorResult = buildInputs.canonicalAuthorResult();
        ImportProcessingResult canonicalPublicationResult = buildInputs.canonicalPublicationResult();
        // H66B two-tier (Phase 1): user-defined canonicalization is a global recompute with no delta support.
        // This service has no ForumBuilder dependency, so it never mutates the forum registry — a user-defined
        // publication's forum resolution can only change when user-defined facts themselves change. So on an
        // incremental batch that produced no user-defined facts (e.g. a Scopus author refresh), skip the global
        // pass; it would only redo identical work. Full maintenance still always runs it.
        boolean userDefinedTouchedByBatch = userDefinedFactResult.getProcessedCount() > 0;
        ImportProcessingResult canonicalUserDefinedResult = (incrementalBatchRun && !userDefinedTouchedByBatch)
                ? new ImportProcessingResult(0)
                : userDefinedCanonicalizationService.rebuildCanonicalFacts();
        // H66B Phase 4a: replay the durable OpenAlex source-facts onto the freshly-rebuilt canonical layer so
        // OpenAlex-origin pubs (and their links onto Scopus/user-defined pubs by DOI) survive a full rebuild.
        // The incremental on-demand path runs its own Tier-2 resolve, so this global replay is full-maintenance only.
        ImportProcessingResult canonicalOpenAlexResult = incrementalBatchRun
                ? new ImportProcessingResult(0)
                : openAlexCanonicalizationService.rebuildCanonicalFacts();
        ImportProcessingResult canonicalCitationResult = buildInputs.canonicalCitationResult();
        ScholardexSourceLinkService.ImportRepairSummary sourceLinkRepair = effectiveOptions.reconcileSourceLinks()
                ? sourceLinkService.reconcileLinks()
                : new ScholardexSourceLinkService.ImportRepairSummary(0L, 0L, 0L);
        ImportProcessingResult edgeRepair = effectiveOptions.reconcileEdges()
                ? reconcileEdges(effectiveOptions)
                : new ImportProcessingResult(0);
        ImportProcessingResult projectionResult = rebuildProjections(effectiveOptions);
        String outcome = (factResult.getErrorCount()
                + userDefinedFactResult.getErrorCount()
                + canonicalAffiliationResult.getErrorCount()
                + canonicalAuthorResult.getErrorCount()
                + canonicalPublicationResult.getErrorCount()
                + canonicalUserDefinedResult.getErrorCount()
                + canonicalOpenAlexResult.getErrorCount()
                + canonicalCitationResult.getErrorCount()
                + projectionResult.getErrorCount()) > 0 ? "failure" : "success";
        long durationNanos = System.nanoTime() - startedAtNanos;
        CanonicalObservabilityMetrics.recordCanonicalBuildRun("all", SOURCE_CANONICAL_MIXED, outcome, durationNanos);
        CanonicalObservabilityMetrics.recordCanonicalBuildRun("all", "USER_DEFINED", outcome, durationNanos);
        log.info("CANONICAL_MAINTENANCE canonical_materialization runId={} batchId={} correlationId={} trigger={} source={} entity=all outcome={} durationMs={} factProcessed={} factErrors={} userDefinedFactProcessed={} userDefinedFactErrors={} canonicalAffiliationProcessed={} canonicalAffiliationErrors={} canonicalAffiliationBatches={} canonicalAuthorProcessed={} canonicalAuthorErrors={} canonicalAuthorBatches={} canonicalPublicationProcessed={} canonicalPublicationErrors={} canonicalPublicationBatches={} canonicalUserDefinedProcessed={} canonicalUserDefinedErrors={} canonicalUserDefinedBatches={} canonicalCitationProcessed={} canonicalCitationErrors={} canonicalCitationBatches={} sourceLinkReconcileUpdated={} sourceLinkReconcileSkipped={} sourceLinkReconcileErrors={} edgeReconcileUpdated={} edgeReconcileSkipped={} edgeReconcileErrors={} projectionProcessed={} projectionErrors={}",
                runId,
                batchId,
                trigger,
                trigger,
                SOURCE_CANONICAL_MIXED,
                outcome,
                durationNanos / 1_000_000L,
                factResult.getProcessedCount(),
                factResult.getErrorCount(),
                userDefinedFactResult.getProcessedCount(),
                userDefinedFactResult.getErrorCount(),
                canonicalAffiliationResult.getProcessedCount(),
                canonicalAffiliationResult.getErrorCount(),
                canonicalAffiliationResult.getBatchesProcessed(),
                canonicalAuthorResult.getProcessedCount(),
                canonicalAuthorResult.getErrorCount(),
                canonicalAuthorResult.getBatchesProcessed(),
                canonicalPublicationResult.getProcessedCount(),
                canonicalPublicationResult.getErrorCount(),
                canonicalPublicationResult.getBatchesProcessed(),
                canonicalUserDefinedResult.getProcessedCount(),
                canonicalUserDefinedResult.getErrorCount(),
                canonicalUserDefinedResult.getBatchesProcessed(),
                canonicalCitationResult.getProcessedCount(),
                canonicalCitationResult.getErrorCount(),
                canonicalCitationResult.getBatchesProcessed(),
                sourceLinkRepair.updated(),
                sourceLinkRepair.skipped(),
                sourceLinkRepair.errors(),
                edgeRepair.getUpdatedCount(),
                edgeRepair.getSkippedCount(),
                edgeRepair.getErrorCount(),
                projectionResult.getProcessedCount(),
                projectionResult.getErrorCount());
    }

    private CanonicalBuildInputs runFullMaintenance(CanonicalBuildOptions canonicalOptions) {
        CanonicalBuildOptions effectiveOptions = canonicalOptions == null ? CanonicalBuildOptions.defaults() : canonicalOptions;
        ImportProcessingResult factResult = factBuilderService.buildFactsFromImportEvents(null);
        ImportProcessingResult userDefinedFactResult = userDefinedFactBuilderService.buildFactsFromImportEvents(null);
        ImportProcessingResult canonicalAffiliationResult = affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalAuthorResult = authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalPublicationResult = publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalCitationResult = citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(effectiveOptions);
        return new CanonicalBuildInputs(
                factResult,
                userDefinedFactResult,
                effectiveOptions,
                canonicalAffiliationResult,
                canonicalAuthorResult,
                canonicalPublicationResult,
                canonicalCitationResult
        );
    }

    private CanonicalBuildInputs runIncrementalBatchMaintenance(String batchId, CanonicalBuildOptions canonicalOptions) {
        CanonicalBuildOptions effectiveOptions = buildIncrementalOptions(batchId, canonicalOptions);
        String sourceBatchId = effectiveOptions.sourceBatchIdFilter();
        ImportProcessingResult factResult = factBuilderService.buildFactsFromImportEvents(sourceBatchId);
        ImportProcessingResult userDefinedFactResult = userDefinedFactBuilderService.buildFactsFromImportEvents(sourceBatchId);
        ImportProcessingResult canonicalAffiliationResult = affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalAuthorResult = authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalPublicationResult = publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalCitationResult = citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(effectiveOptions);
        return new CanonicalBuildInputs(
                factResult,
                userDefinedFactResult,
                effectiveOptions,
                canonicalAffiliationResult,
                canonicalAuthorResult,
                canonicalPublicationResult,
                canonicalCitationResult
        );
    }

    private CanonicalBuildOptions buildIncrementalOptions(String batchId, CanonicalBuildOptions canonicalOptions) {
        CanonicalBuildOptions effectiveOptions = canonicalOptions == null ? CanonicalBuildOptions.defaults() : canonicalOptions;
        if (!isBlank(effectiveOptions.sourceBatchIdFilter())) {
            return new CanonicalBuildOptions(
                    effectiveOptions.chunkSizeOverride(),
                    effectiveOptions.startBatchOverride(),
                    false,
                    effectiveOptions.sourceBatchIdFilter(),
                    effectiveOptions.sourceVersionOverride(),
                    effectiveOptions.reconcileSourceLinks(),
                    effectiveOptions.reconcileEdges()
            );
        }
        if (isBlank(batchId)) {
            return effectiveOptions;
        }
        return new CanonicalBuildOptions(
                effectiveOptions.chunkSizeOverride(),
                effectiveOptions.startBatchOverride(),
                false,
                batchId,
                effectiveOptions.sourceVersionOverride(),
                effectiveOptions.reconcileSourceLinks(),
                effectiveOptions.reconcileEdges()
        );
    }

    private boolean isIncrementalBatchRun(String batchId, CanonicalBuildOptions canonicalOptions) {
        CanonicalBuildOptions effectiveOptions = canonicalOptions == null ? CanonicalBuildOptions.defaults() : canonicalOptions;
        return !isBlank(batchId) || !isBlank(effectiveOptions.sourceBatchIdFilter());
    }

    private ImportProcessingResult reconcileEdges(CanonicalBuildOptions options) {
        if (isBlank(options.sourceBatchIdFilter())) {
            return edgeReconciliationService.reconcileEdges();
        }
        return edgeReconciliationService.reconcileEdges(options.sourceBatchIdFilter());
    }

    private ImportProcessingResult rebuildProjections(CanonicalBuildOptions options) {
        if (isBlank(options.sourceBatchIdFilter())) {
            return projectionBuilderService.rebuildViews();
        }
        return projectionBuilderService.rebuildViewsForBatch(options.sourceBatchIdFilter());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CanonicalBuildInputs(
            ImportProcessingResult factResult,
            ImportProcessingResult userDefinedFactResult,
            CanonicalBuildOptions effectiveOptions,
            ImportProcessingResult canonicalAffiliationResult,
            ImportProcessingResult canonicalAuthorResult,
            ImportProcessingResult canonicalPublicationResult,
            ImportProcessingResult canonicalCitationResult
    ) {
    }
}
