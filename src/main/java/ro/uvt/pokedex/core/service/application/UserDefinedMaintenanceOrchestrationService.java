package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.UserDefinedCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.UserDefinedFactBuilderService;

@Service
@RequiredArgsConstructor
public class UserDefinedMaintenanceOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(UserDefinedMaintenanceOrchestrationService.class);
    private static final String SOURCE_USER_DEFINED = "USER_DEFINED";

    private final UserDefinedFactBuilderService userDefinedFactBuilderService;
    private final UserDefinedCanonicalizationService userDefinedCanonicalizationService;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexEdgeReconciliationService edgeReconciliationService;
    private final ScopusProjectionBuilderService projectionBuilderService;

    public ImportProcessingResult runBuildFactsStep(String batchId) {
        return userDefinedFactBuilderService.buildFactsFromImportEvents(batchId);
    }

    public UserDefinedMaintenanceRunSummary runCanonicalizeStep(
            boolean reconcileSourceLinks,
            boolean reconcileEdges,
            boolean rebuildProjections
    ) {
        return runInternal(false, null, reconcileSourceLinks, reconcileEdges, rebuildProjections);
    }

    public UserDefinedMaintenanceRunSummary runAll(
            String batchId,
            boolean reconcileSourceLinks,
            boolean reconcileEdges,
            boolean rebuildProjections
    ) {
        return runInternal(true, batchId, reconcileSourceLinks, reconcileEdges, rebuildProjections);
    }

    private UserDefinedMaintenanceRunSummary runInternal(
            boolean includeBuildFacts,
            String batchId,
            boolean reconcileSourceLinks,
            boolean reconcileEdges,
            boolean rebuildProjections
    ) {
        long startedAtNanos = System.nanoTime();
        String runId = java.util.UUID.randomUUID().toString();

        ImportProcessingResult buildFacts = includeBuildFacts
                ? userDefinedFactBuilderService.buildFactsFromImportEvents(batchId)
                : new ImportProcessingResult(0);
        ImportProcessingResult canonicalize = userDefinedCanonicalizationService.rebuildCanonicalFacts();
        ScholardexSourceLinkService.ImportRepairSummary sourceLinkReconcile = reconcileSourceLinks
                ? sourceLinkService.reconcileLinks()
                : new ScholardexSourceLinkService.ImportRepairSummary(0L, 0L, 0L);
        ImportProcessingResult edgeReconcile = reconcileEdges
                ? edgeReconciliationService.reconcileEdges()
                : new ImportProcessingResult(0);
        ImportProcessingResult projections = rebuildProjections
                ? projectionBuilderService.rebuildViews()
                : new ImportProcessingResult(0);

        String outcome = (buildFacts.getErrorCount()
                + canonicalize.getErrorCount()
                + (int) sourceLinkReconcile.errors()
                + edgeReconcile.getErrorCount()
                + projections.getErrorCount()) > 0 ? "failure" : "success";
        long durationNanos = System.nanoTime() - startedAtNanos;
        H19CanonicalMetrics.recordCanonicalBuildRun("all", SOURCE_USER_DEFINED, outcome, durationNanos);
        log.info("H19_TRIAGE canonical_build runId={} batchId={} correlationId={} entity=all source=USER_DEFINED outcome={} buildFactsProcessed={} buildFactsErrors={} canonicalizeProcessed={} canonicalizeErrors={} sourceLinkReconcileUpdated={} sourceLinkReconcileSkipped={} sourceLinkReconcileErrors={} edgeReconcileUpdated={} edgeReconcileSkipped={} edgeReconcileErrors={} projectionsProcessed={} projectionsErrors={} durationMs={}",
                runId,
                batchId,
                "N/A",
                outcome,
                buildFacts.getProcessedCount(),
                buildFacts.getErrorCount(),
                canonicalize.getProcessedCount(),
                canonicalize.getErrorCount(),
                sourceLinkReconcile.updated(),
                sourceLinkReconcile.skipped(),
                sourceLinkReconcile.errors(),
                edgeReconcile.getUpdatedCount(),
                edgeReconcile.getSkippedCount(),
                edgeReconcile.getErrorCount(),
                projections.getProcessedCount(),
                projections.getErrorCount(),
                durationNanos / 1_000_000L);

        return new UserDefinedMaintenanceRunSummary(
                buildFacts,
                canonicalize,
                sourceLinkReconcile,
                edgeReconcile,
                projections
        );
    }

    public record UserDefinedMaintenanceRunSummary(
            ImportProcessingResult buildFacts,
            ImportProcessingResult canonicalize,
            ScholardexSourceLinkService.ImportRepairSummary sourceLinkReconcile,
            ImportProcessingResult edgeReconcile,
            ImportProcessingResult projections
    ) {
    }
}
