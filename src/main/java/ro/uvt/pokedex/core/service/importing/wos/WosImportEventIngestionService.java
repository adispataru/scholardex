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
import ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final String TEMP_FILE_PREFIX = "wos-upload-";

    private final WosImportEventRepository importEventRepository;
    private final ObjectMapper objectMapper;
    private final WosOptimizationProperties optimizationProperties;
    private final WosIndexMaintenanceService wosIndexMaintenanceService;
    @Value("${h14.wos.official-json-dir:data/wos-json-1997-2019}")
    private String officialWosJsonDirectory;
    @Value("${h14.wos.gov-ais.password:uefiscdi}")
    private String govAisPassword;

    public WosImportEventIngestionService(
            WosImportEventRepository importEventRepository,
            ObjectMapper objectMapper,
            WosOptimizationProperties optimizationProperties,
            WosIndexMaintenanceService wosIndexMaintenanceService
    ) {
        this.importEventRepository = importEventRepository;
        this.objectMapper = objectMapper;
        this.optimizationProperties = optimizationProperties;
        this.wosIndexMaintenanceService = wosIndexMaintenanceService;
    }

    public ImportProcessingResult ingestDirectory(String directoryPath, String sourceVersionOverride) {
        if (optimizationProperties.isPreflightIndexesEnabled()) {
            wosIndexMaintenanceService.ensureWosIndexesForStage("wos-ingest");
        }
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

    public String resolveEffectiveSourceVersion(String sourceVersionOverride, String sourceName) {
        if (sourceVersionOverride != null && !sourceVersionOverride.isBlank()) {
            return sourceVersionOverride.trim();
        }
        return inferSourceVersion(sourceName);
    }

    public ImportProcessingResult ingestUploadedFile(
            ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadSourceType sourceType,
            String originalFilename,
            String sourceVersionOverride,
            byte[] bytes
    ) {
        if (sourceType == null) {
            throw new IllegalArgumentException("WoS upload source type is required.");
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Uploaded WoS filename is required.");
        }
        String effectiveSourceVersion = resolveEffectiveSourceVersion(sourceVersionOverride, originalFilename);
        return switch (sourceType) {
            case OFFICIAL_JSON -> ingestUploadedOfficialJson(originalFilename, effectiveSourceVersion, bytes);
            case GOVERNMENT_EXCEL -> ingestUploadedGovernmentExcel(originalFilename, effectiveSourceVersion, bytes);
        };
    }

    private void ingestGovernmentAisRisExcel(File dataDir, String sourceVersionOverride, ImportProcessingResult total) {
        File[] files = dataDir.listFiles((d, name) -> name.matches("AIS_\\d{4}\\.xlsx*") || name.matches("RIS_\\d{4}\\.xlsx*"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            int proc0 = total.getProcessedCount();
            int imp0 = total.getImportedCount();
            int upd0 = total.getUpdatedCount();
            int skp0 = total.getSkippedCount();
            int err0 = total.getErrorCount();
            String fileName = file.getName();
            String sourceVersion = sourceVersionOverride != null && !sourceVersionOverride.isBlank()
                    ? sourceVersionOverride
                    : inferSourceVersion(fileName);
            String metricType = fileName.substring(0, 3);
            String year = extractYear(fileName);
            long fileStartedAtNanos = System.nanoTime();
            long readExistingNs = 0L;
            long parseSanitizeNs = 0L;
            long checksumNormalizeNs = 0L;
            long persistBatchNs = 0L;
            long flushCount = 0L;
            try (Workbook workbook = openWorkbook(file, metricType)) {
                Sheet sheet = workbook.getSheetAt(0);
                FormulaEvaluator formulaEvaluator = workbook.getCreationHelper().createFormulaEvaluator();
                DataFormatter dataFormatter = new DataFormatter(Locale.ROOT);
                int numRows = sheet.getPhysicalNumberOfRows();
                long readExistingStartedAtNanos = System.nanoTime();
                Map<String, WosImportEvent> existingByRowItem =
                        loadExistingByRowItem(WosSourceType.GOV_AIS_RIS, fileName, sourceVersion);
                readExistingNs += System.nanoTime() - readExistingStartedAtNanos;
                List<WosImportEvent> toPersist = new ArrayList<>();
                for (int i = 1; i < numRows; i++) {
                    long parseStartedAtNanos = System.nanoTime();
                    Row row = sheet.getRow(i);
                    if (row == null) {
                        continue;
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("metricType", metricType);
                    payload.put("year", year);
                    payload.put("cells", extractCells(row, formulaEvaluator, dataFormatter));
                    if (shouldSkipGovEvent(payload, total, fileName, Integer.toString(i))) {
                        parseSanitizeNs += System.nanoTime() - parseStartedAtNanos;
                        continue;
                    }
                    parseSanitizeNs += System.nanoTime() - parseStartedAtNanos;
                    long processStartedAtNanos = System.nanoTime();
                    processEventFast(
                            WosSourceType.GOV_AIS_RIS,
                            fileName,
                            sourceVersion,
                            Integer.toString(i),
                            "excel-row",
                            payload,
                            existingByRowItem,
                            toPersist,
                            total
                    );
                    checksumNormalizeNs += System.nanoTime() - processStartedAtNanos;
                    long flushDurationNs = flushBatchIfNeeded(toPersist);
                    persistBatchNs += flushDurationNs;
                    if (flushDurationNs > 0) {
                        flushCount++;
                    }
                    maybeLogFileHeartbeat(fileName, total, proc0, imp0, upd0, skp0, err0, fileStartedAtNanos, persistBatchNs, flushCount);
                }
                long flushDurationNs = flushBatch(toPersist);
                persistBatchNs += flushDurationNs;
                if (flushDurationNs > 0) {
                    flushCount++;
                }
            } catch (Exception e) {
                total.markError("file=" + fileName + ", error=" + e.getMessage());
            }
            long totalNs = System.nanoTime() - fileStartedAtNanos;
            log.info("WoS import-events GOV file summary for {}: processed={}, imported={}, updated={}, skipped={}, errors={}, timingsMs[readExisting={}, parse+sanitize={}, checksum+normalize={}, persistBatch={}, total={}], throughputPerSec={}",
                    fileName,
                    total.getProcessedCount() - proc0,
                    total.getImportedCount() - imp0,
                    total.getUpdatedCount() - upd0,
                    total.getSkippedCount() - skp0,
                    total.getErrorCount() - err0,
                    nanosToMillis(readExistingNs),
                    nanosToMillis(parseSanitizeNs),
                    nanosToMillis(checksumNormalizeNs),
                    nanosToMillis(persistBatchNs),
                    nanosToMillis(totalNs),
                    eventsPerSecond(total.getProcessedCount() - proc0, totalNs));
        }
    }

    private ImportProcessingResult ingestUploadedGovernmentExcel(
            String originalFilename,
            String sourceVersion,
            byte[] bytes
    ) {
        ImportProcessingResult total = new ImportProcessingResult(10);
        String metricType = extractGovernmentMetricType(originalFilename);
        String year = extractYear(originalFilename);
        long fileStartedAtNanos = System.nanoTime();
        long readExistingNs = 0L;
        long parseSanitizeNs = 0L;
        long checksumNormalizeNs = 0L;
        long persistBatchNs = 0L;
        long flushCount = 0L;
        Path tempFile = null;
        try {
            tempFile = createTempUploadFile(originalFilename, bytes);
            long readExistingStartedAtNanos = System.nanoTime();
            Map<String, WosImportEvent> existingByRowItem =
                    loadExistingByRowItem(WosSourceType.GOV_AIS_RIS, originalFilename, sourceVersion);
            readExistingNs += System.nanoTime() - readExistingStartedAtNanos;
            List<WosImportEvent> toPersist = new ArrayList<>();
            try (Workbook workbook = openWorkbook(tempFile.toFile(), metricType)) {
                Sheet sheet = workbook.getSheetAt(0);
                FormulaEvaluator formulaEvaluator = workbook.getCreationHelper().createFormulaEvaluator();
                DataFormatter dataFormatter = new DataFormatter(Locale.ROOT);
                int numRows = sheet.getPhysicalNumberOfRows();
                for (int i = 1; i < numRows; i++) {
                    long parseStartedAtNanos = System.nanoTime();
                    Row row = sheet.getRow(i);
                    if (row == null) {
                        continue;
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("metricType", metricType);
                    payload.put("year", year);
                    payload.put("cells", extractCells(row, formulaEvaluator, dataFormatter));
                    if (shouldSkipGovEvent(payload, total, originalFilename, Integer.toString(i))) {
                        parseSanitizeNs += System.nanoTime() - parseStartedAtNanos;
                        continue;
                    }
                    parseSanitizeNs += System.nanoTime() - parseStartedAtNanos;
                    long processStartedAtNanos = System.nanoTime();
                    processEventFast(
                            WosSourceType.GOV_AIS_RIS,
                            originalFilename,
                            sourceVersion,
                            Integer.toString(i),
                            "excel-row",
                            payload,
                            existingByRowItem,
                            toPersist,
                            total
                    );
                    checksumNormalizeNs += System.nanoTime() - processStartedAtNanos;
                    long flushDurationNs = flushBatchIfNeeded(toPersist);
                    persistBatchNs += flushDurationNs;
                    if (flushDurationNs > 0) {
                        flushCount++;
                    }
                    maybeLogFileHeartbeat(originalFilename, total, 0, 0, 0, 0, 0, fileStartedAtNanos, persistBatchNs, flushCount);
                }
                long flushDurationNs = flushBatch(toPersist);
                persistBatchNs += flushDurationNs;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse uploaded WoS government Excel file.", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ingest uploaded WoS government Excel file.", e);
        } finally {
            deleteTempFileQuietly(tempFile);
        }
        long totalNs = System.nanoTime() - fileStartedAtNanos;
        log.info("WoS uploaded GOV file summary for {}: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}, timingsMs[readExisting={}, parse+sanitize={}, checksum+normalize={}, persistBatch={}, total={}], throughputPerSec={}",
                originalFilename,
                total.getProcessedCount(),
                total.getImportedCount(),
                total.getUpdatedCount(),
                total.getSkippedCount(),
                total.getErrorCount(),
                total.getErrorsSample(),
                nanosToMillis(readExistingNs),
                nanosToMillis(parseSanitizeNs),
                nanosToMillis(checksumNormalizeNs),
                nanosToMillis(persistBatchNs),
                nanosToMillis(totalNs),
                eventsPerSecond(total.getProcessedCount(), totalNs));
        return total;
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
            int proc0 = total.getProcessedCount();
            int imp0 = total.getImportedCount();
            int upd0 = total.getUpdatedCount();
            int skp0 = total.getSkippedCount();
            int err0 = total.getErrorCount();
            String fileName = jsonRelativeName(jsonDir, file);
            String sourceVersion = sourceVersionOverride != null && !sourceVersionOverride.isBlank()
                    ? sourceVersionOverride
                    : inferSourceVersion(file.getName());
            long fileStartedAtNanos = System.nanoTime();
            long readExistingNs = 0L;
            long parseSanitizeNs = 0L;
            long checksumNormalizeNs = 0L;
            long persistBatchNs = 0L;
            long flushCount = 0L;
            try {
                long readExistingStartedAtNanos = System.nanoTime();
                Map<String, WosImportEvent> existingByRowItem =
                        loadExistingByRowItem(WosSourceType.OFFICIAL_WOS_EXTRACT, fileName, sourceVersion);
                readExistingNs += System.nanoTime() - readExistingStartedAtNanos;
                List<WosImportEvent> toPersist = new ArrayList<>();
                byte[] bytes = Files.readAllBytes(file.toPath());
                long parseStartedAtNanos = System.nanoTime();
                JsonNode root = objectMapper.readTree(bytes);
                parseSanitizeNs += System.nanoTime() - parseStartedAtNanos;
                if (!root.isArray()) {
                    total.markError("file=" + fileName + ", error=root_not_array");
                    continue;
                }
                Iterator<JsonNode> iterator = root.elements();
                int idx = 0;
                while (iterator.hasNext()) {
                    long sanitizeStartedAtNanos = System.nanoTime();
                    JsonNode item = iterator.next();
                    JsonNode sanitizedItem = sanitizeOfficialJsonIdentity(item);
                    parseSanitizeNs += System.nanoTime() - sanitizeStartedAtNanos;
                    if (sanitizedItem == null) {
                        markIdentitySkipped(total, fileName, Integer.toString(idx));
                        idx++;
                        continue;
                    }
                    long processStartedAtNanos = System.nanoTime();
                    processEventFast(
                            WosSourceType.OFFICIAL_WOS_EXTRACT,
                            fileName,
                            sourceVersion,
                            Integer.toString(idx),
                            "json-item",
                            sanitizedItem,
                            existingByRowItem,
                            toPersist,
                            total
                    );
                    checksumNormalizeNs += System.nanoTime() - processStartedAtNanos;
                    long flushDurationNs = flushBatchIfNeeded(toPersist);
                    persistBatchNs += flushDurationNs;
                    if (flushDurationNs > 0) {
                        flushCount++;
                    }
                    maybeLogFileHeartbeat(fileName, total, proc0, imp0, upd0, skp0, err0, fileStartedAtNanos, persistBatchNs, flushCount);
                    idx++;
                }
                long flushDurationNs = flushBatch(toPersist);
                persistBatchNs += flushDurationNs;
                if (flushDurationNs > 0) {
                    flushCount++;
                }
            } catch (Exception e) {
                total.markError("file=" + fileName + ", error=" + e.getMessage());
            }
            long totalNs = System.nanoTime() - fileStartedAtNanos;
            log.info("WoS import-events official JSON summary for {}: processed={}, imported={}, updated={}, skipped={}, errors={}, timingsMs[readExisting={}, parse+sanitize={}, checksum+normalize={}, persistBatch={}, total={}], throughputPerSec={}",
                    fileName,
                    total.getProcessedCount() - proc0,
                    total.getImportedCount() - imp0,
                    total.getUpdatedCount() - upd0,
                    total.getSkippedCount() - skp0,
                    total.getErrorCount() - err0,
                    nanosToMillis(readExistingNs),
                    nanosToMillis(parseSanitizeNs),
                    nanosToMillis(checksumNormalizeNs),
                    nanosToMillis(persistBatchNs),
                    nanosToMillis(totalNs),
                    eventsPerSecond(total.getProcessedCount() - proc0, totalNs));
        }
    }

    private ImportProcessingResult ingestUploadedOfficialJson(
            String originalFilename,
            String sourceVersion,
            byte[] bytes
    ) {
        ImportProcessingResult total = new ImportProcessingResult(10);
        long fileStartedAtNanos = System.nanoTime();
        long readExistingNs = 0L;
        long parseSanitizeNs = 0L;
        long checksumNormalizeNs = 0L;
        long persistBatchNs = 0L;
        long flushCount = 0L;
        try {
            long readExistingStartedAtNanos = System.nanoTime();
            Map<String, WosImportEvent> existingByRowItem =
                    loadExistingByRowItem(WosSourceType.OFFICIAL_WOS_EXTRACT, originalFilename, sourceVersion);
            readExistingNs += System.nanoTime() - readExistingStartedAtNanos;
            List<WosImportEvent> toPersist = new ArrayList<>();
            long parseStartedAtNanos = System.nanoTime();
            JsonNode root = objectMapper.readTree(bytes);
            parseSanitizeNs += System.nanoTime() - parseStartedAtNanos;
            if (!root.isArray()) {
                throw new IllegalArgumentException("Uploaded WoS official JSON must contain a JSON array.");
            }
            Iterator<JsonNode> iterator = root.elements();
            int idx = 0;
            while (iterator.hasNext()) {
                long sanitizeStartedAtNanos = System.nanoTime();
                JsonNode item = iterator.next();
                JsonNode sanitizedItem = sanitizeOfficialJsonIdentity(item);
                parseSanitizeNs += System.nanoTime() - sanitizeStartedAtNanos;
                if (sanitizedItem == null) {
                    markIdentitySkipped(total, originalFilename, Integer.toString(idx));
                    idx++;
                    continue;
                }
                long processStartedAtNanos = System.nanoTime();
                processEventFast(
                        WosSourceType.OFFICIAL_WOS_EXTRACT,
                        originalFilename,
                        sourceVersion,
                        Integer.toString(idx),
                        "json-item",
                        sanitizedItem,
                        existingByRowItem,
                        toPersist,
                        total
                );
                checksumNormalizeNs += System.nanoTime() - processStartedAtNanos;
                long flushDurationNs = flushBatchIfNeeded(toPersist);
                persistBatchNs += flushDurationNs;
                if (flushDurationNs > 0) {
                    flushCount++;
                }
                maybeLogFileHeartbeat(originalFilename, total, 0, 0, 0, 0, 0, fileStartedAtNanos, persistBatchNs, flushCount);
                idx++;
            }
            long flushDurationNs = flushBatch(toPersist);
            persistBatchNs += flushDurationNs;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse uploaded WoS official JSON file.", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ingest uploaded WoS official JSON file.", e);
        }
        long totalNs = System.nanoTime() - fileStartedAtNanos;
        log.info("WoS uploaded official JSON summary for {}: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}, timingsMs[readExisting={}, parse+sanitize={}, checksum+normalize={}, persistBatch={}, total={}], throughputPerSec={}",
                originalFilename,
                total.getProcessedCount(),
                total.getImportedCount(),
                total.getUpdatedCount(),
                total.getSkippedCount(),
                total.getErrorCount(),
                total.getErrorsSample(),
                nanosToMillis(readExistingNs),
                nanosToMillis(parseSanitizeNs),
                nanosToMillis(checksumNormalizeNs),
                nanosToMillis(persistBatchNs),
                nanosToMillis(totalNs),
                eventsPerSecond(total.getProcessedCount(), totalNs));
        return total;
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
            ImportProcessingResult total
    ) {
        total.markProcessed();
        try {
            String payload = normalizePayload(payloadObject);
            String checksum = sha256Hex(payload);
            Instant now = Instant.now();
            WosImportEvent existing = existingByRowItem.get(sourceRowItem);
            if (existing != null) {
                WosImportEvent event = existing;
                if (checksum.equals(event.getChecksum()) && payload.equals(event.getPayload())) {
                    total.markSkipped("unchanged=" + sourceFile + "#" + sourceRowItem);
                    return;
                }
                // Supersede in place: keep first-ingest time, advance last-write time and version.
                event.setPayloadFormat(payloadFormat);
                event.setPayload(payload);
                event.setChecksum(checksum);
                event.setUpdatedAt(now);
                event.setVersion(event.getVersion() + 1);
                toPersist.add(event);
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
            event.setIngestedAt(now);
            event.setUpdatedAt(now);
            event.setVersion(1);
            toPersist.add(event);
            total.markImported();
        } catch (Exception e) {
            total.markError("source=" + sourceFile + "#" + sourceRowItem + ", error=" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean shouldSkipGovEvent(
            Map<String, Object> payload,
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
            markIdentitySkipped(total, sourceFile, sourceRowItem);
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
            ImportProcessingResult total,
            String sourceFile,
            String sourceRowItem
    ) {
        String message = "invalid-identity-identifiers=" + sourceFile + "#" + sourceRowItem;
        total.markProcessed();
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

    private long flushBatchIfNeeded(List<WosImportEvent> toPersist) {
        if (toPersist.size() >= Math.max(1, optimizationProperties.getIngestPersistBatchSize())) {
            return flushBatch(toPersist);
        }
        return 0L;
    }

    private long flushBatch(List<WosImportEvent> toPersist) {
        if (toPersist.isEmpty()) {
            return 0L;
        }
        long startedAtNanos = System.nanoTime();
        importEventRepository.saveAll(toPersist);
        toPersist.clear();
        return System.nanoTime() - startedAtNanos;
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

    private String extractGovernmentMetricType(String originalFilename) {
        String normalized = originalFilename == null ? "" : originalFilename.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("AIS_")) {
            return "AIS";
        }
        if (normalized.startsWith("RIS_")) {
            return "RIS";
        }
        throw new IllegalArgumentException("WoS government Excel filename must start with AIS_ or RIS_.");
    }

    private Path createTempUploadFile(String originalFilename, byte[] bytes) throws IOException {
        String suffix = "";
        if (originalFilename != null) {
            int dotIndex = originalFilename.lastIndexOf('.');
            if (dotIndex >= 0) {
                suffix = originalFilename.substring(dotIndex);
            }
        }
        Path tempFile = Files.createTempFile(TEMP_FILE_PREFIX, suffix);
        Files.write(tempFile, bytes);
        return tempFile;
    }

    private void deleteTempFileQuietly(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("Failed to delete WoS upload temp file {}", tempFile, e);
        }
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

    private void maybeLogFileHeartbeat(
            String fileName,
            ImportProcessingResult total,
            int proc0, int imp0, int upd0, int skp0, int err0,
            long fileStartedAtNanos,
            long persistBatchNs,
            long flushCount
    ) {
        int heartbeatInterval = Math.max(1, optimizationProperties.getTelemetryHeartbeatInterval());
        int processed = total.getProcessedCount() - proc0;
        if (processed == 0 || processed % heartbeatInterval != 0) {
            return;
        }
        long elapsedNs = System.nanoTime() - fileStartedAtNanos;
        long avgFlushMs = flushCount == 0 ? 0 : nanosToMillis(persistBatchNs / flushCount);
        log.info("WoS ingestion heartbeat [{}]: processed={} imported={} updated={} skipped={} errors={} elapsedMs={} throughputPerSec={} avgFlushMs={}",
                fileName,
                processed,
                total.getImportedCount() - imp0,
                total.getUpdatedCount() - upd0,
                total.getSkippedCount() - skp0,
                total.getErrorCount() - err0,
                nanosToMillis(elapsedNs),
                eventsPerSecond(processed, elapsedNs),
                avgFlushMs);
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private double eventsPerSecond(long processed, long elapsedNs) {
        if (processed <= 0 || elapsedNs <= 0) {
            return 0d;
        }
        return (processed * 1_000_000_000d) / elapsedNs;
    }
}
