package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosIncrementalUploadRequest;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadRunResult;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventIngestionService;

@Service
@RequiredArgsConstructor
@Slf4j
public class WosIncrementalUploadService {

    private static final String H29_2_NOTE =
            "Upload-scoped fact building, upload-scoped category enrichment, and upload-scoped projection rebuild remain tied to the uploaded lineage. WoS onboarding is still excluded from this incremental path.";

    private final WosImportEventIngestionService wosImportEventIngestionService;
    private final WosFactBuilderService wosFactBuilderService;

    public WosUploadRunResult run(WosIncrementalUploadRequest request) {
        String effectiveSourceVersion = wosImportEventIngestionService.resolveEffectiveSourceVersion(
                request.sourceVersion(),
                request.file().originalFilename()
        );
        ImportProcessingResult ingest = wosImportEventIngestionService.ingestUploadedFile(
                request.sourceType(),
                request.file().originalFilename(),
                effectiveSourceVersion,
                request.file().bytes()
        );
        WosFactBuilderService.FactBuildRunResult factBuild = wosFactBuilderService.buildFactsFromImportEventsForSource(
                toModelSourceType(request.sourceType()),
                request.file().originalFilename(),
                effectiveSourceVersion
        );
        log.info(
                "WoS incremental upload completed: sourceType={}, fileName={}, sourceVersion={}, ingestProcessed={}, ingestErrors={}, factProcessed={}, factErrors={}",
                request.sourceType(),
                request.file().originalFilename(),
                effectiveSourceVersion,
                ingest.getProcessedCount(),
                ingest.getErrorCount(),
                factBuild.result().getProcessedCount(),
                factBuild.result().getErrorCount()
        );
        return new WosUploadRunResult(
                request.sourceType(),
                request.file().originalFilename(),
                effectiveSourceVersion,
                ingest,
                factBuild,
                H29_2_NOTE
        );
    }

    private ro.uvt.pokedex.core.model.reporting.wos.WosSourceType toModelSourceType(
            ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadSourceType sourceType
    ) {
        return switch (sourceType) {
            case OFFICIAL_JSON -> ro.uvt.pokedex.core.model.reporting.wos.WosSourceType.OFFICIAL_WOS_EXTRACT;
            case GOVERNMENT_EXCEL -> ro.uvt.pokedex.core.model.reporting.wos.WosSourceType.GOV_AIS_RIS;
        };
    }
}
