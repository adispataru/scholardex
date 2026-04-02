package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.UploadedPayload;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosIncrementalUploadRequest;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadRunResult;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadSourceType;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventIngestionService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosIncrementalUploadServiceTest {

    @Mock
    private WosImportEventIngestionService wosImportEventIngestionService;
    @Mock
    private WosFactBuilderService wosFactBuilderService;

    @Test
    void runExecutesIngestAndFactBuildWithoutCheckpointResume() {
        WosIncrementalUploadService service = new WosIncrementalUploadService(
                wosImportEventIngestionService,
                wosFactBuilderService
        );
        WosIncrementalUploadRequest request = new WosIncrementalUploadRequest(
                WosUploadSourceType.OFFICIAL_JSON,
                null,
                new UploadedPayload("journals-SCIE-year-2024.json", "application/json", "{}".getBytes())
        );

        ImportProcessingResult ingest = new ImportProcessingResult(10);
        ingest.markProcessed();
        ingest.markImported();
        when(wosImportEventIngestionService.resolveEffectiveSourceVersion(null, "journals-SCIE-year-2024.json"))
                .thenReturn("v2024");
        when(wosImportEventIngestionService.ingestUploadedFile(
                eq(WosUploadSourceType.OFFICIAL_JSON),
                eq("journals-SCIE-year-2024.json"),
                eq("v2024"),
                any(byte[].class)
        )).thenReturn(ingest);

        ImportProcessingResult factResult = new ImportProcessingResult(10);
        factResult.markProcessed();
        factResult.markUpdated();
        when(wosFactBuilderService.buildFactsFromImportEventsForSource(eq(WosSourceType.OFFICIAL_WOS_EXTRACT), eq("journals-SCIE-year-2024.json"), eq("v2024")))
                .thenReturn(new WosFactBuilderService.FactBuildRunResult(factResult, 0, 0, 1, false, -1));

        WosUploadRunResult result = service.run(request);

        assertEquals("v2024", result.effectiveSourceVersion());
        assertEquals(1, result.ingest().getImportedCount());
        assertEquals(1, result.factBuild().result().getUpdatedCount());
        assertFalse(result.factBuild().resumedFromCheckpoint());
        assertTrue(result.note().contains("Upload-scoped fact building"));
        verify(wosImportEventIngestionService).resolveEffectiveSourceVersion(null, "journals-SCIE-year-2024.json");
        verify(wosImportEventIngestionService).ingestUploadedFile(
                eq(WosUploadSourceType.OFFICIAL_JSON),
                eq("journals-SCIE-year-2024.json"),
                eq("v2024"),
                any(byte[].class)
        );
        verify(wosFactBuilderService).buildFactsFromImportEventsForSource(eq(WosSourceType.OFFICIAL_WOS_EXTRACT), eq("journals-SCIE-year-2024.json"), eq("v2024"));
    }
}
