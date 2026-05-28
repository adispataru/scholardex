package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.ScopusUploadRunResult;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.UploadedPayload;
import ro.uvt.pokedex.core.service.importing.ScopusDataService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScopusIncrementalUploadService {

    private static final String H29_3_NOTE =
            "Projection rebuild, source-link reconciliation, edge reconciliation, and ensure-indexes were intentionally skipped in H29.3.";

    private final ScopusDataService scopusDataService;
    private final ScopusBigBangMigrationService scopusBigBangMigrationService;

    public ScopusUploadRunResult run(UploadedPayload file) {
        String uploadBatchId = scopusDataService.createUploadBatchId(file.originalFilename());
        ImportProcessingResult publicationIngest =
                scopusDataService.importUploadedScopusDataSync(file.originalFilename(), uploadBatchId, file.bytes());
        ImportProcessingResult citationIngest =
                scopusDataService.importUploadedScopusDataCitationsSync(file.originalFilename(), uploadBatchId, file.bytes());
        ScopusBigBangMigrationService.ScopusBigBangMigrationResult buildFactsRun =
                scopusBigBangMigrationService.runIncrementalUploadBuildStep(uploadBatchId, null);
        MigrationStepResult ingestCombined = mergeImportResults("ingest", publicationIngest, citationIngest);
        MigrationStepResult buildFacts = buildFactsRun.buildFacts();
        log.info(
                "Scopus incremental upload completed: fileName={}, ingestProcessed={}, ingestErrors={}, buildFactsProcessed={}, buildFactsErrors={}",
                file.originalFilename(),
                ingestCombined.processed(),
                ingestCombined.errors(),
                buildFacts == null ? 0 : buildFacts.processed(),
                buildFacts == null ? 0 : buildFacts.errors()
        );
        return new ScopusUploadRunResult(
                file.originalFilename(),
                uploadBatchId,
                publicationIngest,
                citationIngest,
                ingestCombined,
                buildFacts,
                H29_3_NOTE
        );
    }

    public ScopusUploadRunResult runPublisherCsv(UploadedPayload file) {
        String uploadBatchId = scopusDataService.createUploadBatchId(file.originalFilename());
        ImportProcessingResult ingest =
                scopusDataService.importUploadedPublisherCsvSync(file.originalFilename(), uploadBatchId, file.bytes());
        ScopusBigBangMigrationService.ScopusBigBangMigrationResult buildFactsRun =
                scopusBigBangMigrationService.runIncrementalUploadBuildStep(uploadBatchId, null);
        MigrationStepResult ingestStep = singleImportResultToStep("ingest-publisher-csv", ingest);
        MigrationStepResult buildFacts = buildFactsRun.buildFacts();
        log.info(
                "Publisher CSV incremental upload completed: fileName={}, ingestProcessed={}, ingestErrors={}, buildFactsProcessed={}, buildFactsErrors={}",
                file.originalFilename(),
                ingestStep.processed(),
                ingestStep.errors(),
                buildFacts == null ? 0 : buildFacts.processed(),
                buildFacts == null ? 0 : buildFacts.errors()
        );
        return new ScopusUploadRunResult(
                file.originalFilename(),
                uploadBatchId,
                ingest,
                new ImportProcessingResult(0),
                ingestStep,
                buildFacts,
                H29_3_NOTE
        );
    }

    private MigrationStepResult singleImportResultToStep(String stepName, ImportProcessingResult result) {
        return new MigrationStepResult(
                stepName,
                true,
                result.getProcessedCount(),
                result.getImportedCount(),
                result.getUpdatedCount(),
                result.getSkippedCount(),
                result.getErrorCount(),
                null,
                new java.util.ArrayList<>(result.getErrorsSample()),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public MigrationStepResult rebuildProjections(String uploadBatchId) {
        return scopusBigBangMigrationService.runIncrementalUploadProjectionStep(uploadBatchId).buildProjections();
    }

    public ImportProcessingResult reconcileEdges(String uploadBatchId) {
        return scopusBigBangMigrationService.runIncrementalUploadEdgeReconcileStep(uploadBatchId);
    }

    public ScholardexSourceLinkService.ImportRepairSummary repairSourceLinks() {
        return scopusBigBangMigrationService.runSourceLinkReconcileStep();
    }

    private MigrationStepResult mergeImportResults(
            String stepName,
            ImportProcessingResult publicationImport,
            ImportProcessingResult citationImport
    ) {
        java.util.List<String> samples = new java.util.ArrayList<>();
        samples.addAll(publicationImport.getErrorsSample());
        samples.addAll(citationImport.getErrorsSample());
        return new MigrationStepResult(
                stepName,
                true,
                publicationImport.getProcessedCount() + citationImport.getProcessedCount(),
                publicationImport.getImportedCount() + citationImport.getImportedCount(),
                publicationImport.getUpdatedCount() + citationImport.getUpdatedCount(),
                publicationImport.getSkippedCount() + citationImport.getSkippedCount(),
                publicationImport.getErrorCount() + citationImport.getErrorCount(),
                null,
                samples,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
