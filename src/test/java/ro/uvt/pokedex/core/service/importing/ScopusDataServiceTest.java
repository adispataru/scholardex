package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScopusDataServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScopusImportEventIngestionService importEventIngestionService = mock(ScopusImportEventIngestionService.class);
    private final ScopusCanonicalMaterializationService materializationService = mock(ScopusCanonicalMaterializationService.class);
    private final ScopusDataService service = new ScopusDataService(
            mock(ScopusImportEventRepository.class),
            importEventIngestionService,
            materializationService
    );

    @TempDir Path tempDir;

    // --- loadScopusDataIfEmptySync ---

    @Test
    void loadScopusDataIfEmptySync_repoHasData_returnsFalse() {
        ScopusImportEventRepository repo = mock(ScopusImportEventRepository.class);
        when(repo.count()).thenReturn(5L);
        ScopusDataService svc = new ScopusDataService(repo, importEventIngestionService, materializationService);

        assertFalse(svc.loadScopusDataIfEmptySync("/irrelevant"));
        verify(importEventIngestionService, never()).ingest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void loadScopusDataIfEmptySync_repoEmpty_callsMaterializationAndReturnsTrue() throws IOException {
        ScopusImportEventRepository repo = mock(ScopusImportEventRepository.class);
        when(repo.count()).thenReturn(0L);
        ScopusImportEventIngestionService ingestion = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService materialization = mock(ScopusCanonicalMaterializationService.class);
        ScopusDataService svc = new ScopusDataService(repo, ingestion, materialization);

        Path file = tempDir.resolve("scopus.json");
        Files.writeString(file, "{\"eid\":[\"2-s2.0-1\"],\"title\":[\"T\"]}");

        when(ingestion.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("evt-1"));

        assertTrue(svc.loadScopusDataIfEmptySync(file.toString()));
        verify(materialization).rebuildFactsAndViews(eq("bootstrap-empty-load"), isNull(), any());
    }

    // --- importScopusDataSync IO error ---

    @Test
    void importScopusDataSync_ioError_returnsResultWithError() {
        ImportProcessingResult result = service.importScopusDataSync("/no/such/file.json", 0, false);
        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getProcessedCount());
    }

    // --- importScopusDataCitationsSync IO error ---

    @Test
    void importScopusDataCitationsSync_ioError_returnsResultWithError() {
        ImportProcessingResult result = service.importScopusDataCitationsSync("/no/such/file.json");
        assertEquals(1, result.getErrorCount());
    }

    // --- importUploadedScopusDataSync with missing eid ---

    @Test
    void importUploadedScopusDataSync_missingEidField_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importUploadedScopusDataSync("f.json", "b1", "{}".getBytes()));
    }

    // --- importUploadedScopusDataSync with blank eid ---

    @Test
    void importUploadedScopusDataSync_blankEid_countsAsSkipped() {
        String json = "{\"eid\":[\"\"]}";
        var result = service.importUploadedScopusDataSync("f.json", "b1", json.getBytes());
        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getImportedCount());
    }

    // --- importUploadedScopusDataCitationsSync malformed JSON ---

    @Test
    void importUploadedScopusDataCitationsSync_malformedJson_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importUploadedScopusDataCitationsSync("f.json", "b1", "{bad".getBytes()));
    }

    // --- createUploadBatchId normalization ---

    @Test
    void createUploadBatchId_nullFilename_usesUnknownPrefix() {
        String batchId = service.createUploadBatchId(null);
        assertTrue(batchId.startsWith("upload-unknown-"));
    }

    @Test
    void createUploadBatchId_blankFilename_usesUnknownPrefix() {
        String batchId = service.createUploadBatchId("   ");
        assertTrue(batchId.startsWith("upload-unknown-"));
    }

    @Test
    void createUploadBatchId_specialCharsInFilename_areNormalized() {
        String batchId = service.createUploadBatchId("my file (1).json");
        assertTrue(batchId.startsWith("upload-my_file__1_.json-"));
    }

    // --- normalizeOptionalValue ---

    @SuppressWarnings("unchecked")
    @Test
    void normalizeOptionalValue_nullLiteral_mappedToEmpty() {
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("evt-1"));

        String json = "{\"eid\":[\"2-s2.0-1\"],\"title\":[\"null\"]}";
        service.importUploadedScopusDataSync("f.json", "b1", json.getBytes());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(importEventIngestionService).ingest(any(), any(), any(), any(), any(), any(), captor.capture());
        assertEquals("", ((Map<String, Object>) captor.getValue()).get("title"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeOptionalValue_naSlashA_mappedToEmpty() {
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("evt-1"));

        String json = "{\"eid\":[\"2-s2.0-1\"],\"title\":[\"n/a\"]}";
        service.importUploadedScopusDataSync("f.json", "b1", json.getBytes());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(importEventIngestionService).ingest(any(), any(), any(), any(), any(), any(), captor.capture());
        assertEquals("", ((Map<String, Object>) captor.getValue()).get("title"));
    }

    // --- readInt fallback paths ---

    @SuppressWarnings("unchecked")
    @Test
    void readInt_stringNumericCell_parsedCorrectly() {
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("evt-1"));

        // author_count as a string "5" (text node, not numeric) triggers Integer.parseInt fallback
        String json = "{\"eid\":[\"2-s2.0-1\"],\"author_count\":[\"5\"]}";
        service.importUploadedScopusDataSync("f.json", "b1", json.getBytes());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(importEventIngestionService).ingest(any(), any(), any(), any(), any(), any(), captor.capture());
        assertEquals(5, ((Map<String, Object>) captor.getValue()).get("author_count"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void readInt_nonNumericString_treatedAsZero() {
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("evt-1"));

        String json = "{\"eid\":[\"2-s2.0-1\"],\"author_count\":[\"abc\"]}";
        service.importUploadedScopusDataSync("f.json", "b1", json.getBytes());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(importEventIngestionService).ingest(any(), any(), any(), any(), any(), any(), captor.capture());
        assertEquals(0, ((Map<String, Object>) captor.getValue()).get("author_count"));
    }

    // --- extractCitationsFromJson blank eid skip ---

    @Test
    void extractCitationsFromJson_blankEid_citationIsSkipped() {
        // eid[0] is blank → extractCitationsFromJson skips it → 0 processed
        String json = "{\"eid\":[\"\"],\"citing articles\":{\"0\":[{\"eid\":\"2-s2.0-999\"}]}}";
        var result = service.importUploadedScopusDataCitationsSync("f.json", "b1", json.getBytes());
        assertEquals(0, result.getProcessedCount());
    }

    // --- readInt numeric path ---

    @SuppressWarnings("unchecked")
    @Test
    void readInt_numericCell_parsedCorrectly() {
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("evt-1"));

        // author_count as a JSON numeric node → canConvertToInt() = true → fieldNode.asInt()
        String json = "{\"eid\":[\"2-s2.0-1\"],\"author_count\":[7]}";
        service.importUploadedScopusDataSync("f.json", "b1", json.getBytes());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(importEventIngestionService).ingest(any(), any(), any(), any(), any(), any(), captor.capture());
        assertEquals(7, ((Map<String, Object>) captor.getValue()).get("author_count"));
    }

    // --- readRequiredText null node ---

    @Test
    void readRequiredText_nullJsonNode_countsAsSkipped() {
        // eid: [null] → NullNode → isNull() = true → IntegrationException → skipped
        String json = "{\"eid\":[null]}";
        var result = service.importUploadedScopusDataSync("f.json", "b1", json.getBytes());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getImportedCount());
    }

    // --- readDataSize with non-array non-object eid ---

    @Test
    void readDataSize_scalarEid_throwsIllegalArgument() {
        // eid as string scalar — not array and not object → throws
        assertThrows(IllegalArgumentException.class,
                () -> service.importUploadedScopusDataSync("f.json", "b1", "{\"eid\":\"scalar\"}".getBytes()));
    }

    // --- importScopusDataSync/importScopusDataCitationsSync happy path returns non-null ---

    @Test
    void importScopusDataSync_validFile_returnsNonNull() throws IOException {
        Path file = tempDir.resolve("scopus.json");
        Files.writeString(file, "{\"eid\":[\"2-s2.0-1\"]}");
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("evt-1"));

        ImportProcessingResult result = service.importScopusDataSync(file.toString(), 0, false);
        assertNotNull(result);
        assertEquals(1, result.getProcessedCount());
    }

    @Test
    void importScopusDataCitationsSync_validFile_returnsNonNull() throws IOException {
        Path file = tempDir.resolve("scopus.json");
        Files.writeString(file, "{\"eid\":[\"2-s2.0-1\"]}");

        ImportProcessingResult result = service.importScopusDataCitationsSync(file.toString());
        assertNotNull(result);
        assertEquals(0, result.getProcessedCount()); // no citing articles
    }

    // --- applyIngestionOutcome error path ---

    @Test
    void applyIngestionOutcome_errorOutcome_incrementsErrorCount() {
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.error("something failed"));

        String json = "{\"eid\":[\"2-s2.0-1\"]}";
        var result = service.importUploadedScopusDataSync("f.json", "b1", json.getBytes());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getImportedCount());
        assertEquals(0, result.getSkippedCount());
    }

    // --- extractCitationsFromJson with NUMBER citing articles ---

    @Test
    void extractCitationsFromJson_numberCitingArticles_noCitationsProcessed() {
        // "citing articles" entry is a NUMBER node → not array → skipped
        String json = "{\"eid\":[\"2-s2.0-1\"],\"citing articles\":{\"0\":42}}";
        var result = service.importUploadedScopusDataCitationsSync("f.json", "b1", json.getBytes());
        assertEquals(0, result.getProcessedCount());
    }

    // --- existing tests ---

    @Test
    void importUploadedScopusDataSyncUsesUploadSourceLabel() {
        ScopusImportEventRepository repository = mock(ScopusImportEventRepository.class);
        when(repository.insert(any(ScopusImportEvent.class))).thenAnswer(invocation -> {
            ScopusImportEvent event = invocation.getArgument(0);
            event.setId("evt-1");
            return event;
        });
        ScopusDataService uploadService = serviceWithRealIngestion(repository);

        String payload = """
                {
                  "eid": ["2-s2.0-123"],
                  "title": ["Test title"],
                  "source_id": ["SRC-1"],
                  "publicationName": ["Forum A"],
                  "author_count": [1],
                  "citedby_count": [0],
                  "openaccess": [0]
                }
                """;

        var result = uploadService.importUploadedScopusDataSync("scopus.json", "upload-batch-1", payload.getBytes());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScopusImportEvent> eventCaptor = ArgumentCaptor.forClass(ScopusImportEvent.class);
        verify(repository).insert(eventCaptor.capture());
        assertEquals(ScopusImportEntityType.PUBLICATION, eventCaptor.getValue().getEntityType());
        assertEquals("SCOPUS_JSON_UPLOAD", eventCaptor.getValue().getSource());
        assertEquals("2-s2.0-123", eventCaptor.getValue().getSourceRecordId());
        assertEquals("upload-publication-0", eventCaptor.getValue().getCorrelationId());
        assertEquals("json-object", eventCaptor.getValue().getPayloadFormat());
    }

    @Test
    void importUploadedScopusDataSyncAcceptsRowIndexedObjectPayload() {
        ScopusImportEventRepository repository = mock(ScopusImportEventRepository.class);
        when(repository.insert(any(ScopusImportEvent.class))).thenAnswer(invocation -> {
            ScopusImportEvent event = invocation.getArgument(0);
            event.setId("evt-1");
            return event;
        });
        ScopusDataService uploadService = serviceWithRealIngestion(repository);

        String payload = """
                {
                  "eid": {"0": "2-s2.0-123"},
                  "title": {"0": "Test title"},
                  "source_id": {"0": "SRC-1"},
                  "publicationName": {"0": "Forum A"},
                  "author_count": {"0": 1},
                  "citedby_count": {"0": 0},
                  "openaccess": {"0": 0}
                }
                """;

        var result = uploadService.importUploadedScopusDataSync("scopus.json", "upload-batch-1", payload.getBytes());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScopusImportEvent> eventCaptor = ArgumentCaptor.forClass(ScopusImportEvent.class);
        verify(repository).insert(eventCaptor.capture());
        assertEquals("2-s2.0-123", eventCaptor.getValue().getSourceRecordId());
        assertEquals("SCOPUS_JSON_UPLOAD", eventCaptor.getValue().getSource());
    }

    @Test
    void importUploadedScopusDataCitationsSyncUsesUploadSourceLabel() {
        ScopusImportEventRepository repository = mock(ScopusImportEventRepository.class);
        when(repository.insert(any(ScopusImportEvent.class))).thenAnswer(invocation -> {
            ScopusImportEvent event = invocation.getArgument(0);
            event.setId("evt-" + event.getSourceRecordId());
            return event;
        });
        ScopusDataService uploadService = serviceWithRealIngestion(repository);

        String payload = """
                {
                  "eid": ["2-s2.0-123"],
                  "citing articles": {
                    "0": [
                      {"eid": "2-s2.0-999", "title": "Citing title", "source_id": "SRC-2"}
                    ]
                  }
                }
                """;

        var result = uploadService.importUploadedScopusDataCitationsSync("scopus.json", "upload-batch-1", payload.getBytes());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        ArgumentCaptor<ScopusImportEvent> eventCaptor = ArgumentCaptor.forClass(ScopusImportEvent.class);
        verify(repository, times(2)).insert(eventCaptor.capture());
        List<ScopusImportEvent> events = eventCaptor.getAllValues();
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(event -> "SCOPUS_JSON_UPLOAD".equals(event.getSource())));
        assertTrue(events.stream().anyMatch(event ->
                event.getEntityType() == ScopusImportEntityType.PUBLICATION && "2-s2.0-999".equals(event.getSourceRecordId())));
        assertTrue(events.stream().anyMatch(event ->
                event.getEntityType() == ScopusImportEntityType.CITATION && "2-s2.0-123->2-s2.0-999".equals(event.getSourceRecordId())));
    }

    @Test
    void importUploadedScopusDataSyncSkipsDuplicatesUnderUploadSourceLabel() {
        ScopusImportEventRepository repository = mock(ScopusImportEventRepository.class);
        when(repository.insert(any(ScopusImportEvent.class))).thenThrow(new DuplicateKeyException("duplicate"));
        ScopusDataService uploadService = serviceWithRealIngestion(repository);

        String payload = """
                {
                  "eid": ["2-s2.0-123"],
                  "title": ["Test title"],
                  "source_id": ["SRC-1"],
                  "publicationName": ["Forum A"]
                }
                """;

        var result = uploadService.importUploadedScopusDataSync("scopus.json", "upload-batch-1", payload.getBytes());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getImportedCount());
    }

    @Test
    void importUploadedScopusDataSyncThrowsClearErrorOnMalformedJson() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.importUploadedScopusDataSync("scopus.json", "upload-batch-1", "{bad".getBytes())
        );

        assertTrue(error.getMessage().contains("Failed to parse uploaded Scopus JSON file."));
    }

    private ScopusDataService serviceWithRealIngestion(ScopusImportEventRepository repository) {
        return new ScopusDataService(
                repository,
                new ScopusImportEventIngestionService(repository, objectMapper, null),
                mock(ScopusCanonicalMaterializationService.class)
        );
    }
}
