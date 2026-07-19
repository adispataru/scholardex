package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;

import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScopusDataServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScopusImportEventIngestionService importEventIngestionService = mock(ScopusImportEventIngestionService.class);
    private final ScopusCanonicalMaterializationService materializationService = mock(ScopusCanonicalMaterializationService.class);
    private final ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository bookFactRepository =
            mock(ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository.class);
    private final ScopusDataService service = new ScopusDataService(
            new ro.uvt.pokedex.core.service.importing.ImportPathGuard("/"),
            mock(ScopusImportEventRepository.class),
            importEventIngestionService,
            materializationService,
            bookFactRepository
    );

    @TempDir Path tempDir;

    // --- loadScopusDataIfEmptySync ---

    @Test
    void loadScopusDataIfEmptySync_repoHasData_returnsFalse() {
        ScopusImportEventRepository repo = mock(ScopusImportEventRepository.class);
        when(repo.count()).thenReturn(5L);
        ScopusDataService svc = new ScopusDataService(new ro.uvt.pokedex.core.service.importing.ImportPathGuard("/"), repo, importEventIngestionService, materializationService, bookFactRepository);

        assertFalse(svc.loadScopusDataIfEmptySync("/irrelevant"));
        verify(importEventIngestionService, never()).ingest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void loadScopusDataIfEmptySync_repoEmpty_callsMaterializationAndReturnsTrue() throws IOException {
        ScopusImportEventRepository repo = mock(ScopusImportEventRepository.class);
        when(repo.count()).thenReturn(0L);
        ScopusImportEventIngestionService ingestion = mock(ScopusImportEventIngestionService.class);
        ScopusCanonicalMaterializationService materialization = mock(ScopusCanonicalMaterializationService.class);
        ScopusDataService svc = new ScopusDataService(new ro.uvt.pokedex.core.service.importing.ImportPathGuard("/"), repo, ingestion, materialization,
                mock(ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository.class));

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
                new ro.uvt.pokedex.core.service.importing.ImportPathGuard("/"),
                repository,
                new ScopusImportEventIngestionService(repository, objectMapper, null),
                mock(ScopusCanonicalMaterializationService.class),
                bookFactRepository
        );
    }

    // --- H66 A2: CiteScore loader ---

    @SuppressWarnings("unchecked")
    @Test
    void importCiteScore_groupsRowsBySource_unionsAsjc_mapsTypeAndIssn(@TempDir Path tempDir) throws IOException {
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("evt"));

        // Source 100 spans two ASJC sub-subjects (two rows); source 200 is a book series with a quoted,
        // comma-containing publisher. Header column order matches the real CiteScore export.
        String csv = String.join("\n",
                "Scopus Source ID,Title,Type,Scopus ASJC Code (Sub-subject Area),Publisher,Print ISSN,E-ISSN",
                "100,Journal of Things,j,1902,Elsevier,16807316,16807324",
                "100,Journal of Things,j,3107,Elsevier,16807316,16807324",
                "200,Book Series of Stuff,k,1700,\"Springer Nature, Inc.\",,12345678");
        Path file = tempDir.resolve("citescore.csv");
        Files.writeString(file, csv);

        ImportProcessingResult result = service.importCiteScoreCsvFromPath(file.toString(), "batch-cs");

        // Two distinct sources -> two FORUM events (3 rows collapsed).
        assertEquals(2, result.getProcessedCount());
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(importEventIngestionService, times(2)).ingest(
                eq(ScopusImportEntityType.FORUM), any(), any(), eq("batch-cs"), any(), any(), payloadCaptor.capture());

        Map<String, Object> j = payloadCaptor.getAllValues().stream()
                .filter(p -> "100".equals(p.get("source_id"))).findFirst().orElseThrow();
        assertEquals("Journal of Things", j.get("publicationName"));
        assertEquals("journal", j.get("forumType"));
        assertEquals("16807316", j.get("issn"));
        assertEquals("16807324", j.get("eIssn"));
        assertEquals("Elsevier", j.get("publisher"));
        assertEquals("1902;3107", j.get("asjc")); // unioned across the source's two rows

        Map<String, Object> k = payloadCaptor.getAllValues().stream()
                .filter(p -> "200".equals(p.get("source_id"))).findFirst().orElseThrow();
        assertEquals("book-series", k.get("forumType"));
        assertEquals("Springer Nature, Inc.", k.get("publisher")); // quoted comma preserved (RFC-4180)
        assertEquals("1700", k.get("asjc"));
        assertNull(k.get("issn")); // blank print ISSN -> null
    }

    @Test
    void importCiteScore_missingFile_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importCiteScoreCsvFromPath("/no/such/citescore.csv", "b"));
    }

    // --- H66 A6: Scopus Source List loader ---

    private static void setCell(Row row, int col, String value) {
        row.createCell(col).setCellValue(value);
    }

    @SuppressWarnings("unchecked")
    @Test
    void importSourceList_emitsForumEventsWithMappedTypeNormalizedIssnAndAsjc() throws IOException {
        Path xlsx = tempDir.resolve("ext_list.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet src = wb.createSheet("Scopus Sources May 2026");
            String[] h = {"Sourcerecord ID", "Source Title", "ISSN", "EISSN", "Active or Inactive",
                    "Coverage", "x", "x", "x", "x", "x", "x", "Source Type", "x", "x", "x", "x", "x", "Publisher",
                    "x", "All Science Journal Classification Codes (ASJC)"};
            Row hr = src.createRow(0);
            for (int i = 0; i < h.length; i++) setCell(hr, i, h[i]);
            Row r1 = src.createRow(1);
            setCell(r1, 0, "12345"); setCell(r1, 1, "Test Journal"); setCell(r1, 2, "20349130");
            setCell(r1, 3, "22959149"); setCell(r1, 12, "Journal"); setCell(r1, 18, "Acme Press");
            setCell(r1, 20, "1000; 1100");
            Row r2 = src.createRow(2);
            setCell(r2, 0, "999"); setCell(r2, 1, "Old Series"); setCell(r2, 12, "Book Series");
            r2.createCell(2).setCellValue(280836d); // numeric ISSN → leading-zero recovery to 00280836
            setCell(r2, 20, "2200");

            Sheet conf = wb.createSheet("Serial Conf. Proc. with Profile");
            String[] hc = {"Sourcerecord ID", "Source Title", "ISSN", "EISSN", "Titles Discontinued by Scopus",
                    "Coverage", "All Science Journal Classification Codes (ASJC)"};
            Row hcr = conf.createRow(0);
            for (int i = 0; i < hc.length; i++) setCell(hcr, i, hc[i]);
            Row c1 = conf.createRow(1);
            setCell(c1, 0, "777"); setCell(c1, 1, "Test Conf"); setCell(c1, 2, "21612021");
            setCell(c1, 3, "2161203X"); setCell(c1, 6, "1700");

            try (var out = Files.newOutputStream(xlsx)) {
                wb.write(out);
            }
        }

        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("evt"));

        ImportProcessingResult result = service.importSourceListXlsxFromPath(xlsx.toString(), "b1");

        assertEquals(3, result.getProcessedCount());
        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> recordIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(importEventIngestionService, times(3)).ingest(
                eq(ScopusImportEntityType.FORUM), sourceCaptor.capture(), recordIdCaptor.capture(),
                any(), any(), any(), payloadCaptor.capture());

        // all FORUM events tagged with the Source List source
        assertTrue(sourceCaptor.getAllValues().stream().allMatch("SCOPUS_SOURCE_LIST"::equals));

        Map<String, Map<String, Object>> bySource = new java.util.HashMap<>();
        var ids = recordIdCaptor.getAllValues();
        var payloads = payloadCaptor.getAllValues();
        for (int i = 0; i < ids.size(); i++) bySource.put(ids.get(i), (Map<String, Object>) payloads.get(i));

        Map<String, Object> journal = bySource.get("12345");
        assertEquals("journal", journal.get("forumType"));
        assertEquals("20349130", journal.get("issn"));
        assertEquals("22959149", journal.get("eIssn"));
        assertEquals("Acme Press", journal.get("publisher"));
        assertEquals("1000;1100", journal.get("asjc"));

        Map<String, Object> series = bySource.get("999");
        assertEquals("book-series", series.get("forumType"));
        assertEquals("00280836", series.get("issn")); // numeric cell padded back to 8 chars

        Map<String, Object> conf = bySource.get("777");
        assertEquals("conference", conf.get("forumType"));
        assertEquals("21612021", conf.get("issn"));
        assertEquals("2161203X", conf.get("eIssn"));
    }

    @Test
    void importSourceList_missingFile_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importSourceListXlsxFromPath("/no/such/ext_list.xlsx", "b"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importBookList_streamsBooksKeyedByScopusId() throws IOException {
        Path xlsx = tempDir.resolve("books.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Scopus_Books");
            String[] h = {"TITLE", "PRINT ISBN", "ELECTRONIC ISBN", "PUBLISHER", "PUBLICATION YEAR", "ASJC", "SCOPUS ID"};
            Row hr = s.createRow(0);
            for (int i = 0; i < h.length; i++) setCell(hr, i, h[i]);
            Row r1 = s.createRow(1);
            setCell(r1, 0, "A Great Book"); setCell(r1, 1, "1438437676"); setCell(r1, 2, "9781438437675");
            setCell(r1, 3, "SUNY Press"); setCell(r1, 4, "2011"); setCell(r1, 5, "1200; 3300"); setCell(r1, 6, "bk-1");
            Row r2 = s.createRow(2);
            setCell(r2, 0, "Another Book"); setCell(r2, 3, "De Gruyter"); setCell(r2, 6, "bk-2"); // sparse row, no ISBNs
            try (var out = Files.newOutputStream(xlsx)) {
                wb.write(out);
            }
        }
        when(bookFactRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        ImportProcessingResult result = service.importBookListXlsxFromPath(xlsx.toString(), "b1", "2026");

        assertEquals(2, result.getProcessedCount());
        ArgumentCaptor<java.util.List<ScholardexBookFact>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(bookFactRepository, atLeastOnce()).saveAll(captor.capture());
        Map<String, ScholardexBookFact> byId = captor.getAllValues().stream()
                .flatMap(java.util.List::stream)
                .collect(Collectors.toMap(ScholardexBookFact::getId, b -> b));
        ScholardexBookFact b1 = byId.get("bk-1");
        assertEquals("A Great Book", b1.getTitle());
        assertEquals("1438437676", b1.getPrintIsbn());
        assertEquals("9781438437675", b1.getElectronicIsbn());
        assertEquals("SUNY Press", b1.getPublisher());
        assertEquals(Integer.valueOf(2011), b1.getPublicationYear());
        assertEquals(List.of("1200", "3300"), b1.getAsjc());
        assertEquals("SCOPUS_BOOK_LIST", b1.getSource());
        assertEquals("2026", b1.getAsOf());
        assertEquals("bk-2", byId.get("bk-2").getId()); // sparse row still keyed by Scopus ID
    }

    @Test
    void importBookList_missingFile_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importBookListXlsxFromPath("/no/such/books.xlsx", "b", "2026"));
    }
}
