package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
