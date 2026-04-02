package ro.uvt.pokedex.core.service.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IncrementalUpdateUploadFacade {

    private final WosIncrementalUploadService wosIncrementalUploadService;
    private final WosIncrementalFollowUpService wosIncrementalFollowUpService;
    private final ScopusIncrementalUploadService scopusIncrementalUploadService;

    public IncrementalUpdateUploadFacade(
            WosIncrementalUploadService wosIncrementalUploadService,
            WosIncrementalFollowUpService wosIncrementalFollowUpService,
            ScopusIncrementalUploadService scopusIncrementalUploadService
    ) {
        this.wosIncrementalUploadService = wosIncrementalUploadService;
        this.wosIncrementalFollowUpService = wosIncrementalFollowUpService;
        this.scopusIncrementalUploadService = scopusIncrementalUploadService;
    }

    public WosUploadRunResult acceptWosUpload(WosIncrementalUploadRequest request) {
        return wosIncrementalUploadService.run(request);
    }

    public ScopusUploadRunResult acceptScopusUpload(UploadedPayload file) {
        return scopusIncrementalUploadService.run(file);
    }

    public ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult enrichWosUploadCategoryRankings(
            WosUploadSourceType sourceType,
            String sourceFile,
            String sourceVersion
    ) {
        return wosIncrementalFollowUpService.enrichCategoryRankings(sourceType, sourceFile, sourceVersion);
    }

    public ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult rebuildWosUploadProjections(
            WosUploadSourceType sourceType,
            String sourceFile,
            String sourceVersion
    ) {
        return wosIncrementalFollowUpService.rebuildProjections(sourceType, sourceFile, sourceVersion);
    }

    public ro.uvt.pokedex.core.service.importing.model.MigrationStepResult rebuildScopusUploadProjections(String uploadBatchId) {
        return scopusIncrementalUploadService.rebuildProjections(uploadBatchId);
    }

    public ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult reconcileScopusUploadEdges(String uploadBatchId) {
        return scopusIncrementalUploadService.reconcileEdges(uploadBatchId);
    }

    public ScholardexSourceLinkService.ImportRepairSummary repairScopusSourceLinks() {
        return scopusIncrementalUploadService.repairSourceLinks();
    }

    public enum WosUploadSourceType {
        OFFICIAL_JSON,
        GOVERNMENT_EXCEL
    }

    public record UploadedPayload(
            String originalFilename,
            String contentType,
            byte[] bytes
    ) {
        public int sizeBytes() {
            return bytes == null ? 0 : bytes.length;
        }
    }

    public record WosIncrementalUploadRequest(
            WosUploadSourceType sourceType,
            String sourceVersion,
            UploadedPayload file
    ) {
    }

    public record UploadAcceptanceResult(
            String sourceLabel,
            String originalFilename,
            String summary
    ) {
    }

    public record WosUploadRunResult(
            WosUploadSourceType sourceType,
            String originalFilename,
            String effectiveSourceVersion,
            ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult ingest,
            ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService.FactBuildRunResult factBuild,
            String note
    ) {
    }

    public record ScopusUploadRunResult(
            String originalFilename,
            String uploadBatchId,
            ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult publicationIngest,
            ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult citationIngest,
            ro.uvt.pokedex.core.service.importing.model.MigrationStepResult ingestCombined,
            ro.uvt.pokedex.core.service.importing.model.MigrationStepResult buildFacts,
            String note
    ) {
    }
}
