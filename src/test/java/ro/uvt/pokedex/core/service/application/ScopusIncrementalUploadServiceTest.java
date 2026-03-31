package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.ScopusUploadRunResult;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.UploadedPayload;
import ro.uvt.pokedex.core.service.importing.ScopusDataService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusIncrementalUploadServiceTest {

    @Mock
    private ScopusDataService scopusDataService;
    @Mock
    private ScopusBigBangMigrationService scopusBigBangMigrationService;

    @Test
    void runExecutesUploadIngestAndBuildFactsWithoutCheckpoint() {
        ScopusIncrementalUploadService service = new ScopusIncrementalUploadService(
                scopusDataService,
                scopusBigBangMigrationService
        );
        UploadedPayload payload = new UploadedPayload("scopus.json", "application/json", "{}".getBytes());

        ImportProcessingResult publicationIngest = result(1, 1, 0, 0, 0);
        ImportProcessingResult citationIngest = result(2, 1, 0, 1, 0);
        when(scopusDataService.createUploadBatchId("scopus.json")).thenReturn("upload-scopus-json-1");
        when(scopusDataService.importUploadedScopusDataSync(eq("scopus.json"), eq("upload-scopus-json-1"), eq(payload.bytes()))).thenReturn(publicationIngest);
        when(scopusDataService.importUploadedScopusDataCitationsSync(eq("scopus.json"), eq("upload-scopus-json-1"), eq(payload.bytes()))).thenReturn(citationIngest);

        ImportProcessingResult buildFactsResult = result(3, 1, 1, 1, 0);
        when(scopusBigBangMigrationService.runIncrementalUploadBuildStep("upload-scopus-json-1", null))
                .thenReturn(new ScopusBigBangMigrationService.ScopusBigBangMigrationResult(
                        "ignored",
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        null,
                        ro.uvt.pokedex.core.service.importing.model.MigrationStepResult.executed("build-facts", buildFactsResult),
                        null,
                        null,
                        null
                ));

        ScopusUploadRunResult result = service.run(payload);

        assertEquals("scopus.json", result.originalFilename());
        assertEquals("upload-scopus-json-1", result.uploadBatchId());
        assertEquals(3, result.ingestCombined().processed());
        assertEquals(2, result.ingestCombined().imported());
        assertEquals(3, result.buildFacts().processed());
        assertTrue(result.note().contains("intentionally skipped"));
        verify(scopusDataService).createUploadBatchId("scopus.json");
        verify(scopusDataService).importUploadedScopusDataSync(eq("scopus.json"), eq("upload-scopus-json-1"), eq(payload.bytes()));
        verify(scopusDataService).importUploadedScopusDataCitationsSync(eq("scopus.json"), eq("upload-scopus-json-1"), eq(payload.bytes()));
        verify(scopusBigBangMigrationService).runIncrementalUploadBuildStep("upload-scopus-json-1", null);
        verify(scopusBigBangMigrationService, never()).runBuildProjectionsStep();
    }

    @Test
    void rebuildProjectionsDelegatesToIncrementalProjectionStepOnly() {
        ScopusIncrementalUploadService service = new ScopusIncrementalUploadService(
                scopusDataService,
                scopusBigBangMigrationService
        );
        ImportProcessingResult projections = result(2, 1, 1, 0, 0);
        when(scopusBigBangMigrationService.runIncrementalUploadProjectionStep("upload-scopus-json-1"))
                .thenReturn(new ScopusBigBangMigrationService.ScopusBigBangMigrationResult(
                        "ignored",
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        null,
                        null,
                        ro.uvt.pokedex.core.service.importing.model.MigrationStepResult.executed("build-projections", projections),
                        null,
                        null
                ));

        var result = service.rebuildProjections("upload-scopus-json-1");

        assertEquals(2, result.processed());
        verify(scopusBigBangMigrationService).runIncrementalUploadProjectionStep("upload-scopus-json-1");
        verify(scopusBigBangMigrationService, never()).runBuildProjectionsStep();
    }

    private ImportProcessingResult result(int processed, int imported, int updated, int skipped, int errors) {
        ImportProcessingResult result = new ImportProcessingResult(10);
        for (int i = 0; i < processed; i++) {
            result.markProcessed();
        }
        for (int i = 0; i < imported; i++) {
            result.markImported();
        }
        for (int i = 0; i < updated; i++) {
            result.markUpdated();
        }
        for (int i = 0; i < skipped; i++) {
            result.markSkipped("s" + i);
        }
        for (int i = 0; i < errors; i++) {
            result.markError("e" + i);
        }
        return result;
    }
}
