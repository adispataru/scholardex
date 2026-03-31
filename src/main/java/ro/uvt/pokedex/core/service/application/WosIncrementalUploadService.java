package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosIncrementalUploadRequest;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadRunResult;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventIngestionService;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class WosIncrementalUploadService {

    private static final String H29_2_NOTE =
            "Category enrichment, projections, and WoS onboarding were intentionally skipped in H29.2.";

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
        String runId = "wos-incremental-upload-" + Instant.now().toEpochMilli();
        WosFactBuilderService.FactBuildRunResult factBuild = wosFactBuilderService.buildFactsFromImportEventsWithCheckpoint(
                null,
                false,
                runId,
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
}
