package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeReconciliationService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

@Service
@RequiredArgsConstructor
public class ScopusCanonicalMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(ScopusCanonicalMaterializationService.class);

    private final ScopusFactBuilderService factBuilderService;
    private final UserDefinedFactBuilderService userDefinedFactBuilderService;
    private final ScholardexAffiliationCanonicalizationService affiliationCanonicalizationService;
    private final ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    private final ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    private final UserDefinedCanonicalizationService userDefinedCanonicalizationService;
    private final ScholardexCitationCanonicalizationService citationCanonicalizationService;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexEdgeReconciliationService edgeReconciliationService;
    private final ScopusProjectionBuilderService projectionBuilderService;

    public void rebuildFactsAndViews(String trigger) {
        rebuildFactsAndViews(trigger, null, CanonicalBuildOptions.defaults());
    }

    public void rebuildFactsAndViews(String trigger, String batchId) {
        rebuildFactsAndViews(trigger, batchId, CanonicalBuildOptions.defaults());
    }

    public void rebuildFactsAndViews(String trigger, String batchId, CanonicalBuildOptions canonicalOptions) {
        String runId = java.util.UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();
        ImportProcessingResult factResult = factBuilderService.buildFactsFromImportEvents(batchId);
        ImportProcessingResult userDefinedFactResult = userDefinedFactBuilderService.buildFactsFromImportEvents(batchId);
        CanonicalBuildOptions effectiveOptions = canonicalOptions == null ? CanonicalBuildOptions.defaults() : canonicalOptions;
        ImportProcessingResult canonicalAffiliationResult = affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalAuthorResult = authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalPublicationResult = publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalUserDefinedResult = userDefinedCanonicalizationService.rebuildCanonicalFacts();
        ImportProcessingResult canonicalCitationResult = citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(effectiveOptions);
        ScholardexSourceLinkService.ImportRepairSummary sourceLinkRepair = effectiveOptions.reconcileSourceLinks()
                ? sourceLinkService.reconcileLinks()
                : new ScholardexSourceLinkService.ImportRepairSummary(0L, 0L, 0L);
        ImportProcessingResult edgeRepair = effectiveOptions.reconcileEdges()
                ? edgeReconciliationService.reconcileEdges()
                : new ImportProcessingResult(0);
        ImportProcessingResult projectionResult = projectionBuilderService.rebuildViews();
        String outcome = (factResult.getErrorCount()
                + userDefinedFactResult.getErrorCount()
                + canonicalAffiliationResult.getErrorCount()
                + canonicalAuthorResult.getErrorCount()
                + canonicalPublicationResult.getErrorCount()
                + canonicalUserDefinedResult.getErrorCount()
                + canonicalCitationResult.getErrorCount()
                + projectionResult.getErrorCount()) > 0 ? "failure" : "success";
        long durationNanos = System.nanoTime() - startedAtNanos;
        H19CanonicalMetrics.recordCanonicalBuildRun("all", "SCOPUS", outcome, durationNanos);
        log.info("H19_TRIAGE canonical_materialization runId={} batchId={} correlationId={} trigger={} source=SCOPUS entity=all outcome={} durationMs={} factProcessed={} factErrors={} userDefinedFactProcessed={} userDefinedFactErrors={} canonicalAffiliationProcessed={} canonicalAffiliationErrors={} canonicalAffiliationBatches={} canonicalAuthorProcessed={} canonicalAuthorErrors={} canonicalAuthorBatches={} canonicalPublicationProcessed={} canonicalPublicationErrors={} canonicalPublicationBatches={} canonicalUserDefinedProcessed={} canonicalUserDefinedErrors={} canonicalUserDefinedBatches={} canonicalCitationProcessed={} canonicalCitationErrors={} canonicalCitationBatches={} sourceLinkReconcileUpdated={} sourceLinkReconcileSkipped={} sourceLinkReconcileErrors={} edgeReconcileUpdated={} edgeReconcileSkipped={} edgeReconcileErrors={} projectionProcessed={} projectionErrors={}",
                runId,
                batchId,
                trigger,
                trigger,
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
}
