package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WosImportEventIngestionService {
    private static final Logger log = LoggerFactory.getLogger(WosImportEventIngestionService.class);
    private static final Pattern YEAR_FROM_FILENAME = Pattern.compile(".*?(\\d{4}).*");

    private final WosImportEventRepository importEventRepository;
    private final ObjectMapper objectMapper;

    public WosImportEventIngestionService(WosImportEventRepository importEventRepository, ObjectMapper objectMapper) {
        this.importEventRepository = importEventRepository;
        this.objectMapper = objectMapper;
    }

    public ImportProcessingResult ingestDirectory(String directoryPath, String sourceVersionOverride) {
        ImportProcessingResult total = new ImportProcessingResult(20);
        File dataDir = new File(directoryPath);
        ingestGovernmentAisRisExcel(dataDir, sourceVersionOverride, total);
        ingestOfficialWosJson(dataDir, sourceVersionOverride, total);
        log.info("WoS import-events ingestion summary: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                total.getProcessedCount(), total.getImportedCount(), total.getUpdatedCount(), total.getSkippedCount(),
                total.getErrorCount(), total.getErrorsSample());
        return total;
    }

    private void ingestGovernmentAisRisExcel(File dataDir, String sourceVersionOverride, ImportProcessingResult total) {
        File[] files = dataDir.listFiles((d, name) -> name.matches("AIS_\\d{4}\\.xlsx*") || name.matches("RIS_\\d{4}\\.xlsx*"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            ImportProcessingResult fileResult = new ImportProcessingResult(10);
            String fileName = file.getName();
            String sourceVersion = sourceVersionOverride != null && !sourceVersionOverride.isBlank()
                    ? sourceVersionOverride
                    : inferSourceVersion(fileName);
            String metricType = fileName.substring(0, 3);
            String year = extractYear(fileName);
            try (FileInputStream fis = new FileInputStream(file); Workbook workbook = WorkbookFactory.create(fis)) {
                Sheet sheet = workbook.getSheetAt(0);
                int numRows = sheet.getPhysicalNumberOfRows();
                for (int i = 1; i < numRows; i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) {
                        continue;
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("metricType", metricType);
                    payload.put("year", year);
                    payload.put("cells", extractCells(row));
                    processEvent(
                            WosSourceType.GOV_AIS_RIS,
                            fileName,
                            sourceVersion,
                            Integer.toString(i),
                            "excel-row",
                            payload,
                            fileResult,
                            total
                    );
                }
            } catch (Exception e) {
                fileResult.markError("file=" + fileName + ", error=" + e.getMessage());
                total.markError("file=" + fileName + ", error=" + e.getMessage());
            }
            log.info("WoS import-events GOV file summary for {}: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                    fileName,
                    fileResult.getProcessedCount(),
                    fileResult.getImportedCount(),
                    fileResult.getUpdatedCount(),
                    fileResult.getSkippedCount(),
                    fileResult.getErrorCount(),
                    fileResult.getErrorsSample());
        }
    }

    private void ingestOfficialWosJson(File dataDir, String sourceVersionOverride, ImportProcessingResult total) {
        File jsonDir = new File(dataDir, "wos-json-1997-2019");
        if (!jsonDir.exists() || !jsonDir.isDirectory()) {
            return;
        }
        File[] files = jsonDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            ImportProcessingResult fileResult = new ImportProcessingResult(10);
            String fileName = "wos-json-1997-2019/" + file.getName();
            String sourceVersion = sourceVersionOverride != null && !sourceVersionOverride.isBlank()
                    ? sourceVersionOverride
                    : inferSourceVersion(file.getName());
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                JsonNode root = objectMapper.readTree(bytes);
                if (!root.isArray()) {
                    fileResult.markError("file=" + fileName + ", error=root_not_array");
                    total.markError("file=" + fileName + ", error=root_not_array");
                    continue;
                }
                Iterator<JsonNode> iterator = root.elements();
                int idx = 0;
                while (iterator.hasNext()) {
                    JsonNode item = iterator.next();
                    processEvent(
                            WosSourceType.OFFICIAL_WOS_EXTRACT,
                            fileName,
                            sourceVersion,
                            Integer.toString(idx),
                            "json-item",
                            item,
                            fileResult,
                            total
                    );
                    idx++;
                }
            } catch (Exception e) {
                fileResult.markError("file=" + fileName + ", error=" + e.getMessage());
                total.markError("file=" + fileName + ", error=" + e.getMessage());
            }
            log.info("WoS import-events official JSON summary for {}: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                    fileName,
                    fileResult.getProcessedCount(),
                    fileResult.getImportedCount(),
                    fileResult.getUpdatedCount(),
                    fileResult.getSkippedCount(),
                    fileResult.getErrorCount(),
                    fileResult.getErrorsSample());
        }
    }

    private void processEvent(
            WosSourceType sourceType,
            String sourceFile,
            String sourceVersion,
            String sourceRowItem,
            String payloadFormat,
            Object payloadObject,
            ImportProcessingResult fileResult,
            ImportProcessingResult total
    ) {
        fileResult.markProcessed();
        total.markProcessed();
        try {
            String payload = normalizePayload(payloadObject);
            String checksum = sha256Hex(payload);
            Optional<WosImportEvent> existing = importEventRepository.findBySourceTypeAndSourceFileAndSourceVersionAndSourceRowItem(
                    sourceType, sourceFile, sourceVersion, sourceRowItem
            );
            if (existing.isPresent()) {
                WosImportEvent event = existing.get();
                if (checksum.equals(event.getChecksum()) && payload.equals(event.getPayload())) {
                    fileResult.markSkipped("unchanged=" + sourceFile + "#" + sourceRowItem);
                    total.markSkipped("unchanged=" + sourceFile + "#" + sourceRowItem);
                    return;
                }
                event.setPayloadFormat(payloadFormat);
                event.setPayload(payload);
                event.setChecksum(checksum);
                event.setIngestedAt(Instant.now());
                importEventRepository.save(event);
                fileResult.markUpdated();
                total.markUpdated();
                return;
            }
            WosImportEvent event = new WosImportEvent();
            event.setSourceType(sourceType);
            event.setSourceFile(sourceFile);
            event.setSourceVersion(sourceVersion);
            event.setSourceRowItem(sourceRowItem);
            event.setPayloadFormat(payloadFormat);
            event.setPayload(payload);
            event.setChecksum(checksum);
            event.setIngestedAt(Instant.now());
            importEventRepository.save(event);
            fileResult.markImported();
            total.markImported();
        } catch (Exception e) {
            fileResult.markError("source=" + sourceFile + "#" + sourceRowItem + ", error=" + e.getMessage());
            total.markError("source=" + sourceFile + "#" + sourceRowItem + ", error=" + e.getMessage());
        }
    }

    private String normalizePayload(Object payload) throws JsonProcessingException {
        return objectMapper.writeValueAsString(payload);
    }

    static String buildEventKey(WosSourceType sourceType, String sourceFile, String sourceVersion, String sourceRowItem) {
        return String.join("|",
                sourceType == null ? "null" : sourceType.name(),
                sourceFile == null ? "" : sourceFile,
                sourceVersion == null ? "" : sourceVersion,
                sourceRowItem == null ? "" : sourceRowItem
        );
    }

    private String extractYear(String filename) {
        Matcher matcher = YEAR_FROM_FILENAME.matcher(filename);
        return matcher.matches() ? matcher.group(1) : "unknown";
    }

    private String inferSourceVersion(String sourceName) {
        String year = extractYear(sourceName);
        return "v" + year;
    }

    private Map<String, Object> extractCells(Row row) {
        Map<String, Object> cells = new LinkedHashMap<>();
        short first = row.getFirstCellNum();
        short last = row.getLastCellNum();
        if (first < 0 || last < 0) {
            return cells;
        }
        for (int c = first; c < last; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String key = "c" + c;
            cells.put(key, cellToValue(cell));
        }
        return cells;
    }

    private Object cellToValue(Cell cell) {
        CellType cellType = cell.getCellType();
        if (cellType == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        if (cellType == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }
        String value = cell.toString();
        return value == null ? "" : value.trim();
    }

    private String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
