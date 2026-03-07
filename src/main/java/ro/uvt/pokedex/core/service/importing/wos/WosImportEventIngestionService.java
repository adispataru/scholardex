package ro.uvt.pokedex.core.service.importing.wos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WosImportEventIngestionService {
    private static final Logger log = LoggerFactory.getLogger(WosImportEventIngestionService.class);
    private static final Pattern YEAR_FROM_FILENAME = Pattern.compile(".*?(\\d{4}).*");
    private static final int PERSIST_BATCH_SIZE = 1_000;

    private final WosImportEventRepository importEventRepository;
    private final ObjectMapper objectMapper;
    @Value("${h14.wos.official-json-dir:data/wos-json-1997-2019}")
    private String officialWosJsonDirectory;
    @Value("${h14.wos.gov-ais.password:uefiscdi}")
    private String govAisPassword;

    public WosImportEventIngestionService(
            WosImportEventRepository importEventRepository,
            ObjectMapper objectMapper
    ) {
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

    public WosIngestionPreview previewDirectory(String directoryPath, String sourceVersionOverride) {
        File dataDir = new File(directoryPath);
        int filesScanned = 0;
        int plannedEvents = 0;
        List<String> samples = new ArrayList<>();
        int errors = 0;

        File[] govFiles = dataDir.listFiles((d, name) -> name.matches("AIS_\\d{4}\\.xlsx*") || name.matches("RIS_\\d{4}\\.xlsx*"));
        if (govFiles != null) {
            for (File file : govFiles) {
                filesScanned++;
                String sourceVersion = sourceVersionOverride != null && !sourceVersionOverride.isBlank()
                        ? sourceVersionOverride
                        : inferSourceVersion(file.getName());
                try (Workbook workbook = openWorkbook(file, "AIS")) {
                    Sheet sheet = workbook.getSheetAt(0);
                    int rowCount = Math.max(0, sheet.getPhysicalNumberOfRows() - 1);
                    plannedEvents += rowCount;
                    if (samples.size() < 20) {
                        samples.add("gov-file=" + file.getName() + ", sourceVersion=" + sourceVersion + ", rows=" + rowCount);
                    }
                } catch (Exception e) {
                    errors++;
                    if (samples.size() < 20) {
                        samples.add("gov-file=" + file.getName() + ", preview-error=" + e.getMessage());
                    }
                }
            }
        }

        File jsonDir = resolveOfficialWosJsonDir(dataDir);
        if (jsonDir.exists() && jsonDir.isDirectory()) {
            File[] jsonFiles = jsonDir.listFiles((d, name) -> name.endsWith(".json"));
            if (jsonFiles != null) {
                for (File file : jsonFiles) {
                    filesScanned++;
                    String sourceVersion = sourceVersionOverride != null && !sourceVersionOverride.isBlank()
                            ? sourceVersionOverride
                            : inferSourceVersion(file.getName());
                    try {
                        JsonNode root = objectMapper.readTree(Files.readAllBytes(file.toPath()));
                        int itemCount = root.isArray() ? root.size() : 0;
                        plannedEvents += itemCount;
                        if (samples.size() < 20) {
                            samples.add("json-file=" + jsonRelativeName(jsonDir, file)
                                    + ", sourceVersion=" + sourceVersion + ", items=" + itemCount);
                        }
                    } catch (Exception e) {
                        errors++;
                        if (samples.size() < 20) {
                            samples.add("json-file=" + jsonRelativeName(jsonDir, file) + ", preview-error=" + e.getMessage());
                        }
                    }
                }
            }
        }

        return new WosIngestionPreview(filesScanned, plannedEvents, errors, List.copyOf(samples));
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
            try (Workbook workbook = openWorkbook(file, metricType)) {
                Sheet sheet = workbook.getSheetAt(0);
                FormulaEvaluator formulaEvaluator = workbook.getCreationHelper().createFormulaEvaluator();
                DataFormatter dataFormatter = new DataFormatter(Locale.ROOT);
                int numRows = sheet.getPhysicalNumberOfRows();
                Map<String, WosImportEvent> existingByRowItem =
                        loadExistingByRowItem(WosSourceType.GOV_AIS_RIS, fileName, sourceVersion);
                List<WosImportEvent> toPersist = new ArrayList<>();
                for (int i = 1; i < numRows; i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) {
                        continue;
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("metricType", metricType);
                    payload.put("year", year);
                    payload.put("cells", extractCells(row, formulaEvaluator, dataFormatter));
                    if (shouldSkipGovEvent(payload, fileResult, total, fileName, Integer.toString(i))) {
                        continue;
                    }
                    processEventFast(
                            WosSourceType.GOV_AIS_RIS,
                            fileName,
                            sourceVersion,
                            Integer.toString(i),
                            "excel-row",
                            payload,
                            existingByRowItem,
                            toPersist,
                            fileResult,
                            total
                    );
                    flushBatchIfNeeded(toPersist);
                }
                flushBatch(toPersist);
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
        File jsonDir = resolveOfficialWosJsonDir(dataDir);
        if (!jsonDir.exists() || !jsonDir.isDirectory()) {
            return;
        }
        File[] files = jsonDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            ImportProcessingResult fileResult = new ImportProcessingResult(10);
            String fileName = jsonRelativeName(jsonDir, file);
            String sourceVersion = sourceVersionOverride != null && !sourceVersionOverride.isBlank()
                    ? sourceVersionOverride
                    : inferSourceVersion(file.getName());
            try {
                Map<String, WosImportEvent> existingByRowItem =
                        loadExistingByRowItem(WosSourceType.OFFICIAL_WOS_EXTRACT, fileName, sourceVersion);
                List<WosImportEvent> toPersist = new ArrayList<>();
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
                    JsonNode sanitizedItem = sanitizeOfficialJsonIdentity(item);
                    if (sanitizedItem == null) {
                        markIdentitySkipped(fileResult, total, fileName, Integer.toString(idx));
                        idx++;
                        continue;
                    }
                    processEventFast(
                            WosSourceType.OFFICIAL_WOS_EXTRACT,
                            fileName,
                            sourceVersion,
                            Integer.toString(idx),
                            "json-item",
                            sanitizedItem,
                            existingByRowItem,
                            toPersist,
                            fileResult,
                            total
                    );
                    flushBatchIfNeeded(toPersist);
                    idx++;
                }
                flushBatch(toPersist);
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

    private void processEventFast(
            WosSourceType sourceType,
            String sourceFile,
            String sourceVersion,
            String sourceRowItem,
            String payloadFormat,
            Object payloadObject,
            Map<String, WosImportEvent> existingByRowItem,
            List<WosImportEvent> toPersist,
            ImportProcessingResult fileResult,
            ImportProcessingResult total
    ) {
        fileResult.markProcessed();
        total.markProcessed();
        try {
            String payload = normalizePayload(payloadObject);
            String checksum = sha256Hex(payload);
            WosImportEvent existing = existingByRowItem.get(sourceRowItem);
            if (existing != null) {
                WosImportEvent event = existing;
                if (checksum.equals(event.getChecksum()) && payload.equals(event.getPayload())) {
                    fileResult.markSkipped("unchanged=" + sourceFile + "#" + sourceRowItem);
                    total.markSkipped("unchanged=" + sourceFile + "#" + sourceRowItem);
                    return;
                }
                event.setPayloadFormat(payloadFormat);
                event.setPayload(payload);
                event.setChecksum(checksum);
                event.setIngestedAt(Instant.now());
                toPersist.add(event);
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
            toPersist.add(event);
            fileResult.markImported();
            total.markImported();
        } catch (Exception e) {
            fileResult.markError("source=" + sourceFile + "#" + sourceRowItem + ", error=" + e.getMessage());
            total.markError("source=" + sourceFile + "#" + sourceRowItem + ", error=" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean shouldSkipGovEvent(
            Map<String, Object> payload,
            ImportProcessingResult fileResult,
            ImportProcessingResult total,
            String sourceFile,
            String sourceRowItem
    ) {
        Object metricTypeRaw = payload.get("metricType");
        Object yearRaw = payload.get("year");
        Object cellsRaw = payload.get("cells");
        if (!(cellsRaw instanceof Map<?, ?> cellsAny)) {
            return false;
        }
        Map<String, Object> cells = (Map<String, Object>) cellsAny;
        Set<String> idColumns = govIdentityColumns(asString(metricTypeRaw), asString(yearRaw));
        if (idColumns.isEmpty()) {
            return false;
        }

        boolean hasAnyToken = false;
        boolean hasValidToken = false;
        for (String column : idColumns) {
            String raw = asString(cells.get(column));
            String normalized = WosCanonicalContractSupport.normalizeIssnToken(raw);
            if (raw != null && !raw.isBlank()) {
                hasAnyToken = true;
            }
            if (normalized != null) {
                hasValidToken = true;
            } else if (raw != null && !raw.isBlank()) {
                cells.put(column, "");
            }
        }

        if (hasAnyToken && !hasValidToken) {
            markIdentitySkipped(fileResult, total, sourceFile, sourceRowItem);
            return true;
        }
        return false;
    }

    private JsonNode sanitizeOfficialJsonIdentity(JsonNode node) {
        if (node == null || !node.isObject()) {
            return node;
        }
        com.fasterxml.jackson.databind.node.ObjectNode objectNode = node.deepCopy();
        String rawIssn = textOrNull(objectNode.get("issn"));
        String rawEIssn = firstNonBlank(textOrNull(objectNode.get("eissn")), textOrNull(objectNode.get("eIssn")));

        String normalizedIssn = WosCanonicalContractSupport.normalizeIssnToken(rawIssn);
        String normalizedEIssn = WosCanonicalContractSupport.normalizeIssnToken(rawEIssn);

        boolean hasAnyToken = (rawIssn != null && !rawIssn.isBlank()) || (rawEIssn != null && !rawEIssn.isBlank());
        if (hasAnyToken && normalizedIssn == null && normalizedEIssn == null) {
            return null;
        }

        if (normalizedIssn == null) {
            objectNode.putNull("issn");
        }
        if (normalizedEIssn == null) {
            objectNode.putNull("eissn");
            objectNode.putNull("eIssn");
        }
        return objectNode;
    }

    private void markIdentitySkipped(
            ImportProcessingResult fileResult,
            ImportProcessingResult total,
            String sourceFile,
            String sourceRowItem
    ) {
        String message = "invalid-identity-identifiers=" + sourceFile + "#" + sourceRowItem;
        fileResult.markProcessed();
        total.markProcessed();
        fileResult.markSkipped(message);
        total.markSkipped(message);
    }

    private Set<String> govIdentityColumns(String metricTypeRaw, String yearRaw) {
        if (metricTypeRaw == null || yearRaw == null) {
            return Set.of();
        }
        String metricType = metricTypeRaw.trim().toUpperCase(Locale.ROOT);
        int year;
        try {
            year = Integer.parseInt(yearRaw.trim());
        } catch (Exception e) {
            return Set.of();
        }
        if ("AIS".equals(metricType)) {
            if (year >= 2020 && year <= 2023) {
                return Set.of("c1", "c2");
            }
            if (year >= 2014 && year <= 2017) {
                return Set.of("c2");
            }
            return Set.of("c1");
        }
        if ("RIS".equals(metricType)) {
            if (year >= 2020) {
                return Set.of("c1", "c2");
            }
            return Set.of("c1");
        }
        return Set.of();
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asText();
    }

    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        if (right != null && !right.isBlank()) {
            return right;
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizePayload(Object payload) throws JsonProcessingException {
        return objectMapper.writeValueAsString(payload);
    }

    private void flushBatchIfNeeded(List<WosImportEvent> toPersist) {
        if (toPersist.size() >= PERSIST_BATCH_SIZE) {
            flushBatch(toPersist);
        }
    }

    private void flushBatch(List<WosImportEvent> toPersist) {
        if (toPersist.isEmpty()) {
            return;
        }
        importEventRepository.saveAll(toPersist);
        toPersist.clear();
    }

    private Map<String, WosImportEvent> loadExistingByRowItem(
            WosSourceType sourceType,
            String sourceFile,
            String sourceVersion
    ) {
        List<WosImportEvent> existing = importEventRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(
                sourceType, sourceFile, sourceVersion
        );
        Map<String, WosImportEvent> byRowItem = new LinkedHashMap<>();
        for (WosImportEvent event : existing) {
            if (event.getSourceRowItem() == null) {
                continue;
            }
            byRowItem.put(event.getSourceRowItem(), event);
        }
        return byRowItem;
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

    private Map<String, Object> extractCells(Row row, FormulaEvaluator formulaEvaluator, DataFormatter dataFormatter) {
        Map<String, Object> cells = new LinkedHashMap<>();
        short first = row.getFirstCellNum();
        short last = row.getLastCellNum();
        if (first < 0 || last < 0) {
            return cells;
        }
        for (int c = first; c < last; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String key = "c" + c;
            cells.put(key, cellToValue(cell, formulaEvaluator, dataFormatter));
        }
        return cells;
    }

    private Object cellToValue(Cell cell, FormulaEvaluator formulaEvaluator, DataFormatter dataFormatter) {
        CellType cellType = cell.getCellType();
        if (cellType == CellType.FORMULA) {
            try {
                CellValue evaluated = formulaEvaluator.evaluate(cell);
                if (evaluated != null) {
                    if (evaluated.getCellType() == CellType.NUMERIC) {
                        return evaluated.getNumberValue();
                    }
                    if (evaluated.getCellType() == CellType.BOOLEAN) {
                        return evaluated.getBooleanValue();
                    }
                    if (evaluated.getCellType() == CellType.STRING) {
                        return trimOrEmpty(evaluated.getStringValue());
                    }
                }
            } catch (Exception ignored) {
                // External references can fail evaluation locally; fallback to cached result.
            }
            CellType cachedType = cell.getCachedFormulaResultType();
            if (cachedType == CellType.NUMERIC) {
                return cell.getNumericCellValue();
            }
            if (cachedType == CellType.BOOLEAN) {
                return cell.getBooleanCellValue();
            }
            if (cachedType == CellType.STRING) {
                return trimOrEmpty(cell.getStringCellValue());
            }
            return trimOrEmpty(dataFormatter.formatCellValue(cell, formulaEvaluator));
        }
        if (cellType == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        if (cellType == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }
        if (cellType == CellType.STRING) {
            return trimOrEmpty(cell.getStringCellValue());
        }
        return trimOrEmpty(dataFormatter.formatCellValue(cell));
    }

    private String trimOrEmpty(String value) {
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

    private File resolveOfficialWosJsonDir(File dataDir) {
        File localCandidate = new File(dataDir, "wos-json-1997-2019");
        if (localCandidate.exists() && localCandidate.isDirectory()) {
            return localCandidate;
        }
        if (officialWosJsonDirectory == null || officialWosJsonDirectory.isBlank()) {
            return localCandidate;
        }
        return new File(officialWosJsonDirectory);
    }

    private String jsonRelativeName(File jsonDir, File file) {
        if (jsonDir == null || file == null) {
            return "wos-json-1997-2019/unknown";
        }
        String dirName = jsonDir.getName();
        if (dirName == null || dirName.isBlank()) {
            dirName = "wos-json-1997-2019";
        }
        return dirName + "/" + file.getName();
    }

    private Workbook openWorkbook(File file, String metricType) throws IOException {
        if ("AIS".equals(metricType) && govAisPassword != null && !govAisPassword.isBlank()) {
            try {
                return WorkbookFactory.create(file, govAisPassword, true);
            } catch (Exception ignored) {
                // Fallback for unencrypted AIS fixtures in tests/local.
            }
        }
        return WorkbookFactory.create(file, null, true);
    }

    public record WosIngestionPreview(
            int filesScanned,
            int plannedEvents,
            int errorCount,
            List<String> samples
    ) {
    }
}
