package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WosImportEventIngestionServiceTest {

    @Test
    void eventKeyIsDeterministic() {
        String key1 = WosImportEventIngestionService.buildEventKey(WosSourceType.GOV_AIS_RIS, "AIS_2024.xlsx", "v2024", "12");
        String key2 = WosImportEventIngestionService.buildEventKey(WosSourceType.GOV_AIS_RIS, "AIS_2024.xlsx", "v2024", "12");
        assertEquals(key1, key2);
    }

    @Test
    void ingestDirectoryImportsExcelAndOfficialJsonEvents() throws Exception {
        Path dir = Files.createTempDirectory("wos-events");
        createSampleExcel(dir.resolve("AIS_2024.xlsx"), 1.1);
        createSampleExcel(dir.resolve("RIS_2024.xlsx"), 2.2);
        createSampleJson(dir.resolve("wos-json-1997-2019"), "journals-SCIE-year-2019.json");

        EventStore store = new EventStore();
        WosImportEventRepository repository = repositoryMock(store);
        WosImportEventIngestionService service = new WosImportEventIngestionService(repository, new ObjectMapper());

        ImportProcessingResult result = service.ingestDirectory(dir.toString(), null);

        assertEquals(7, result.getProcessedCount());
        assertEquals(7, result.getImportedCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(store.containsSourceType(WosSourceType.GOV_AIS_RIS));
        assertTrue(store.containsSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT));
    }

    @Test
    void rerunIsIdempotentAndChangesProduceUpdates() throws Exception {
        Path dir = Files.createTempDirectory("wos-events-rerun");
        Path ais = dir.resolve("AIS_2024.xlsx");
        createSampleExcel(ais, 1.1);
        createSampleJson(dir.resolve("wos-json-1997-2019"), "journals-SSCI-year-2018.json");

        EventStore store = new EventStore();
        WosImportEventRepository repository = repositoryMock(store);
        WosImportEventIngestionService service = new WosImportEventIngestionService(repository, new ObjectMapper());

        ImportProcessingResult first = service.ingestDirectory(dir.toString(), "batch-1");
        int sizeAfterFirst = store.size();
        ImportProcessingResult second = service.ingestDirectory(dir.toString(), "batch-1");

        assertEquals(sizeAfterFirst, store.size());
        assertEquals(0, second.getImportedCount());
        assertEquals(0, second.getUpdatedCount());
        assertTrue(second.getSkippedCount() > 0);
        assertEquals(0, second.getErrorCount());

        createSampleExcel(ais, 9.9);
        ImportProcessingResult third = service.ingestDirectory(dir.toString(), "batch-1");

        assertEquals(sizeAfterFirst, store.size());
        assertTrue(third.getUpdatedCount() > 0);
        assertEquals(0, third.getImportedCount());
        assertEquals(0, third.getErrorCount());
        assertTrue(first.getImportedCount() > 0);
    }

    @Test
    void ingestDirectoryUsesConfiguredOfficialJsonDirectoryWhenMissingUnderLoadedDir() throws Exception {
        Path loadedDir = Files.createTempDirectory("wos-events-loaded");
        Path externalJsonDir = Files.createTempDirectory("wos-events-external-json");
        createSampleExcel(loadedDir.resolve("AIS_2024.xlsx"), 1.1);
        createSampleJson(externalJsonDir, "journals-SCIE-year-2019.json");

        EventStore store = new EventStore();
        WosImportEventRepository repository = repositoryMock(store);
        WosImportEventIngestionService service = new WosImportEventIngestionService(repository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "officialWosJsonDirectory", externalJsonDir.toString());

        ImportProcessingResult result = service.ingestDirectory(loadedDir.toString(), null);

        assertTrue(result.getImportedCount() > 0);
        assertTrue(store.containsSourceType(WosSourceType.GOV_AIS_RIS));
        assertTrue(store.containsSourceType(WosSourceType.OFFICIAL_WOS_EXTRACT));
    }

    @Test
    void ingestDirectoryEvaluatesFormulaCellsInsteadOfPersistingFormulaText() throws Exception {
        Path dir = Files.createTempDirectory("wos-events-formula");
        createFormulaExcel(dir.resolve("AIS_2024.xlsx"));

        EventStore store = new EventStore();
        WosImportEventRepository repository = repositoryMock(store);
        WosImportEventIngestionService service = new WosImportEventIngestionService(repository, new ObjectMapper());

        ImportProcessingResult result = service.ingestDirectory(dir.toString(), null);

        assertEquals(1, result.getImportedCount());
        WosImportEvent event = store.get(WosSourceType.GOV_AIS_RIS, "AIS_2024.xlsx", "v2024", "1");
        JsonNode payload = new ObjectMapper().readTree(event.getPayload());
        assertEquals("Journal Formula", payload.path("cells").path("c0").asText());
    }

    @Test
    void ingestDirectoryUsesCachedValueForExternalReferenceFormula() throws Exception {
        Path dir = Files.createTempDirectory("wos-events-external-formula");
        createExternalFormulaExcelWithCachedValue(dir.resolve("AIS_2020.xlsx"));

        EventStore store = new EventStore();
        WosImportEventRepository repository = repositoryMock(store);
        WosImportEventIngestionService service = new WosImportEventIngestionService(repository, new ObjectMapper());

        ImportProcessingResult result = service.ingestDirectory(dir.toString(), null);

        assertEquals(1, result.getImportedCount());
        WosImportEvent event = store.get(WosSourceType.GOV_AIS_RIS, "AIS_2020.xlsx", "v2020", "1");
        JsonNode payload = new ObjectMapper().readTree(event.getPayload());
        assertEquals("Journal Cached", payload.path("cells").path("c0").asText());
    }

    @Test
    void ingestDirectoryUsesCachedValuesFromEncryptedAisFixture() throws Exception {
        Path dir = Files.createTempDirectory("wos-events-ais-2020-fixture");
        copyResource("/wos/AIS_2020-test.xlsx", dir.resolve("AIS_2020.xlsx"));

        EventStore store = new EventStore();
        WosImportEventRepository repository = repositoryMock(store);
        WosImportEventIngestionService service = new WosImportEventIngestionService(repository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "govAisPassword", "uefiscdi");

        ImportProcessingResult result = service.ingestDirectory(dir.toString(), null);

        assertEquals(3, result.getImportedCount());
        assertJournalTitle(store, "AIS_2020.xlsx", "v2020", "1", "ULTRASOUND IN OBSTETRICS & GYNECOLOGY");
        assertJournalTitle(store, "AIS_2020.xlsx", "v2020", "2", "ULTRASCHALL IN DER MEDIZIN");
        assertJournalTitle(store, "AIS_2020.xlsx", "v2020", "3", "ULTRASONICS SONOCHEMISTRY");
    }

    @Test
    void ingestDirectorySkipsRowsWithOnlyInvalidIssnTokensAndClearsInvalidSibling() throws Exception {
        Path dir = Files.createTempDirectory("wos-events-invalid-issn");
        createAis2020IdentityEdgeCaseExcel(dir.resolve("AIS_2020.xlsx"));

        EventStore store = new EventStore();
        WosImportEventRepository repository = repositoryMock(store);
        WosImportEventIngestionService service = new WosImportEventIngestionService(repository, new ObjectMapper());

        ImportProcessingResult result = service.ingestDirectory(dir.toString(), null);

        assertEquals(2, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getSkippedCount());

        assertEquals(1, store.list(WosSourceType.GOV_AIS_RIS, "AIS_2020.xlsx", "v2020").size());
        WosImportEvent persisted = store.get(WosSourceType.GOV_AIS_RIS, "AIS_2020.xlsx", "v2020", "2");
        assertNotNull(persisted);
        JsonNode payload = new ObjectMapper().readTree(persisted.getPayload());
        assertEquals("1234-5678", payload.path("cells").path("c1").asText());
        assertEquals("", payload.path("cells").path("c2").asText());
    }

    @Test
    void ingestDirectorySkipsOfficialJsonItemsWithOnlyInvalidIssnAndClearsInvalidSibling() throws Exception {
        Path dir = Files.createTempDirectory("wos-events-json-invalid-issn");
        Path jsonDir = dir.resolve("wos-json-1997-2019");
        Files.createDirectories(jsonDir);
        Files.writeString(jsonDir.resolve("journals-SCIE-year-2019.json"), """
                [
                  {"journalTitle":"Bad","year":2019,"edition":"SCIE","issn":"********","eissn":"********","articleInfluenceScore":1.2,"categoryName":"ACOUSTICS"},
                  {"journalTitle":"Good","year":2019,"edition":"SCIE","issn":"1234-5678","eissn":"********","articleInfluenceScore":0.9,"categoryName":"ECONOMICS"}
                ]
                """);

        EventStore store = new EventStore();
        WosImportEventRepository repository = repositoryMock(store);
        WosImportEventIngestionService service = new WosImportEventIngestionService(repository, new ObjectMapper());

        ImportProcessingResult result = service.ingestDirectory(dir.toString(), null);

        assertEquals(2, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getSkippedCount());

        assertEquals(1, store.list(WosSourceType.OFFICIAL_WOS_EXTRACT,
                "wos-json-1997-2019/journals-SCIE-year-2019.json", "v2019").size());
        WosImportEvent persisted = store.get(WosSourceType.OFFICIAL_WOS_EXTRACT,
                "wos-json-1997-2019/journals-SCIE-year-2019.json", "v2019", "1");
        assertNotNull(persisted);
        JsonNode payload = new ObjectMapper().readTree(persisted.getPayload());
        assertEquals("1234-5678", payload.path("issn").asText());
        assertTrue(payload.path("eissn").isNull());
    }

    private void assertJournalTitle(EventStore store, String sourceFile, String sourceVersion, String rowItem, String expectedTitle)
            throws Exception {
        WosImportEvent event = store.get(WosSourceType.GOV_AIS_RIS, sourceFile, sourceVersion, rowItem);
        assertNotNull(event);
        JsonNode payload = new ObjectMapper().readTree(event.getPayload());
        assertEquals(expectedTitle, payload.path("cells").path("c0").asText());
    }

    private void copyResource(String resourcePath, Path target) throws Exception {
        try (InputStream input = WosImportEventIngestionServiceTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(input);
            Files.copy(input, target);
        }
    }

    private WosImportEventRepository repositoryMock(EventStore store) {
        WosImportEventRepository repository = mock(WosImportEventRepository.class);
        when(repository.findBySourceTypeAndSourceFileAndSourceVersionAndSourceRowItem(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(
                        invocation.getArgument(0, WosSourceType.class),
                        invocation.getArgument(1, String.class),
                        invocation.getArgument(2, String.class),
                        invocation.getArgument(3, String.class)
                )));
        when(repository.findAllBySourceTypeAndSourceFileAndSourceVersion(any(), any(), any()))
                .thenAnswer(invocation -> store.list(
                        invocation.getArgument(0, WosSourceType.class),
                        invocation.getArgument(1, String.class),
                        invocation.getArgument(2, String.class)
                ));
        when(repository.save(any(WosImportEvent.class)))
                .thenAnswer(invocation -> {
                    WosImportEvent event = invocation.getArgument(0, WosImportEvent.class);
                    if (event.getId() == null) {
                        event.setId("event-" + store.size() + "-" + event.getSourceRowItem());
                    }
                    store.put(event);
                    return event;
                });
        when(repository.saveAll(any()))
                .thenAnswer(invocation -> {
                    Iterable<WosImportEvent> iterable = invocation.getArgument(0);
                    for (WosImportEvent event : iterable) {
                        if (event.getId() == null) {
                            event.setId("event-" + store.size() + "-" + event.getSourceRowItem());
                        }
                        store.put(event);
                    }
                    return iterable;
                });
        return repository;
    }

    private void createSampleExcel(Path file, double scoreValue) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Title");
            header.createCell(1).setCellValue("ISSN");
            header.createCell(2).setCellValue("Value");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("Journal A");
            row1.createCell(1).setCellValue("1234-5678");
            row1.createCell(2).setCellValue(scoreValue);

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Journal B");
            row2.createCell(1).setCellValue("8765-4321");
            row2.createCell(2).setCellValue(5.0);

            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
        }
    }

    private void createSampleJson(Path directory, String filename) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve(filename);
        String content = """
                [
                  {"journalTitle":"Journal A","year":2019,"edition":"SCIE","issn":"1234-5678","articleInfluenceScore":1.2,"categoryName":"ACOUSTICS"},
                  {"journalTitle":"Journal B","year":2019,"edition":"SCIE","issn":"8765-4321","articleInfluenceScore":0.9,"categoryName":"ECONOMICS"},
                  {"journalTitle":"Journal C","year":2019,"edition":"SSCI","issn":"1111-2222","articleInfluenceScore":0.7,"categoryName":"SOCIOLOGY"}
                ]
                """;
        Files.writeString(file, content);
    }

    private void createFormulaExcel(Path file) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Title");
            header.createCell(1).setCellValue("ISSN");
            header.createCell(2).setCellValue("Value");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellFormula("CONCATENATE(\"Journal\", \" Formula\")");
            row1.createCell(1).setCellValue("1234-5678");
            row1.createCell(2).setCellValue(1.1);

            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
        }
    }

    private void createExternalFormulaExcelWithCachedValue(Path file) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Title");
            header.createCell(1).setCellValue("ISSN");
            header.createCell(2).setCellValue("Value");

            Row row1 = sheet.createRow(1);
            var formulaCell = row1.createCell(0);
            formulaCell.setCellFormula("CONCATENATE('/missing/path/[formule2021.xlsx]AIS.cuartile.formule'!A2847)");
            formulaCell.setCellValue("Journal Cached");
            row1.createCell(1).setCellValue("1234-5678");
            row1.createCell(2).setCellValue(1.1);

            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
        }
    }

    private void createAis2020IdentityEdgeCaseExcel(Path file) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Title");
            header.createCell(1).setCellValue("ISSN");
            header.createCell(2).setCellValue("eISSN");
            header.createCell(3).setCellValue("Value");
            header.createCell(4).setCellValue("Edition");
            header.createCell(5).setCellValue("Category");
            header.createCell(6).setCellValue("Quarter");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("Invalid Both");
            row1.createCell(1).setCellValue("********");
            row1.createCell(2).setCellValue("********");
            row1.createCell(3).setCellValue(1.1);
            row1.createCell(4).setCellValue("SCIE");
            row1.createCell(5).setCellValue("ACOUSTICS");
            row1.createCell(6).setCellValue(1.0);

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Mixed Valid");
            row2.createCell(1).setCellValue("1234-5678");
            row2.createCell(2).setCellValue("********");
            row2.createCell(3).setCellValue(2.2);
            row2.createCell(4).setCellValue("SCIE");
            row2.createCell(5).setCellValue("ACOUSTICS");
            row2.createCell(6).setCellValue(2.0);

            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
        }
    }

    private static final class EventStore {
        private final Map<String, WosImportEvent> events = new ConcurrentHashMap<>();

        void put(WosImportEvent event) {
            String key = WosImportEventIngestionService.buildEventKey(
                    event.getSourceType(),
                    event.getSourceFile(),
                    event.getSourceVersion(),
                    event.getSourceRowItem()
            );
            events.put(key, event);
        }

        WosImportEvent get(WosSourceType type, String file, String version, String rowItem) {
            String key = WosImportEventIngestionService.buildEventKey(type, file, version, rowItem);
            return events.get(key);
        }

        boolean containsSourceType(WosSourceType sourceType) {
            return events.values().stream().anyMatch(e -> e.getSourceType() == sourceType);
        }

        int size() {
            return events.size();
        }

        List<WosImportEvent> list(WosSourceType sourceType, String sourceFile, String sourceVersion) {
            return events.values().stream()
                    .filter(e -> e.getSourceType() == sourceType)
                    .filter(e -> sourceFile.equals(e.getSourceFile()))
                    .filter(e -> sourceVersion.equals(e.getSourceVersion()))
                    .toList();
        }
    }
}
