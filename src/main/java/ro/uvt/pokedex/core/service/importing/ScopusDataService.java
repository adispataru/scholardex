package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptions;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;
import ro.uvt.pokedex.core.service.integration.IntegrationErrorCode;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ScopusDataService {

    private static final Logger logger = LoggerFactory.getLogger(ScopusDataService.class);
    private static final int DEFAULT_ERROR_SAMPLE_SIZE = 20;
    private static final String PAYLOAD_FORMAT_JSON_OBJECT = "json-object";
    private static final String SOURCE_SCOPUS_JSON_BOOTSTRAP = "SCOPUS_JSON_BOOTSTRAP";
    private static final String SOURCE_SCOPUS_JSON_UPLOAD = "SCOPUS_JSON_UPLOAD";
    private static final String SOURCE_SCOPUS_PUBLISHER_CSV_UPLOAD = "SCOPUS_PUBLISHER_CSV_UPLOAD";
    private static final String SOURCE_SCOPUS_CITESCORE_LIST = "SCOPUS_CITESCORE_LIST";
    private static final String SOURCE_SCOPUS_SOURCE_LIST = "SCOPUS_SOURCE_LIST";
    private static final String SOURCE_SCOPUS_BOOK_LIST = "SCOPUS_BOOK_LIST";
    private static final int BOOK_FLUSH_CHUNK = 1_000;
    private static final int BOOK_HEARTBEAT = 50_000;
    private static final int INGEST_HEARTBEAT = 5_000;
    private static final int CITATION_INGEST_BATCH_SIZE = 1_000;
    private static final CanonicalBuildOptions BOOTSTRAP_FULL_RESCAN_OPTIONS =
            new CanonicalBuildOptions(null, null, true, null, null, false, false);

    private final ImportPathGuard importPathGuard;
    private final ScopusImportEventRepository importEventRepository;
    private final ScopusImportEventIngestionService importEventIngestionService;
    private final ScopusCanonicalMaterializationService canonicalMaterializationService;
    private final ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository bookFactRepository;

    @Async("taskExecutor")
    public void loadScopusDataIfEmpty(String scopusDataFile) {
        loadScopusDataIfEmptySync(scopusDataFile);
    }

    public boolean loadScopusDataIfEmptySync(String scopusDataFile) {
        if (importEventRepository.count() == 0) {
            importScopusDataSync(scopusDataFile, 0, false);
            importScopusDataCitationsSync(scopusDataFile);
            canonicalMaterializationService.rebuildFactsAndViews("bootstrap-empty-load", null, BOOTSTRAP_FULL_RESCAN_OPTIONS);
            return true;
        }
        return false;
    }

    @Async("taskExecutor")
    public void loadAdditionalScopusData(String scopusDataFile) {
        loadAdditionalScopusDataSync(scopusDataFile);
    }

    public void loadAdditionalScopusDataSync(String scopusDataFile) {
        importScopusDataSync(scopusDataFile, 0, true);
        importScopusDataCitationsSync(scopusDataFile);
        canonicalMaterializationService.rebuildFactsAndViews("bootstrap-additional-load", null, BOOTSTRAP_FULL_RESCAN_OPTIONS);
    }

    @Async("taskExecutor")
    public void importScopusData(String jsonFilePath, long count, boolean checkExisting) {
        importScopusDataSync(jsonFilePath, count, checkExisting);
    }

    public ImportProcessingResult importScopusDataSync(String jsonFilePath, long count, boolean checkExisting) {
        try {
            JsonNode rootNode = new ObjectMapper().readTree(new File(jsonFilePath));
            return importScopusDataFromRoot(
                    rootNode,
                    count,
                    SOURCE_SCOPUS_JSON_BOOTSTRAP,
                    "bootstrap-publications-" + new File(jsonFilePath).getName() + "-" + System.currentTimeMillis(),
                    "bootstrap-publication-"
            );
        } catch (IOException e) {
            logger.error("Error reading the JSON file: ", e);
            ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
            result.markError("scopus-publication-import-io-error=" + e.getMessage());
            return result;
        }
    }

    public ImportProcessingResult importUploadedScopusDataSync(String originalFilename, String batchId, byte[] jsonBytes) {
        try {
            JsonNode rootNode = new ObjectMapper().readTree(jsonBytes);
            return importScopusDataFromRoot(
                    rootNode,
                    0,
                    SOURCE_SCOPUS_JSON_UPLOAD,
                    batchId,
                    "upload-publication-"
            );
        } catch (IOException e) {
            logger.error("Error reading uploaded Scopus JSON file: {}", originalFilename, e);
            throw new IllegalArgumentException("Failed to parse uploaded Scopus JSON file.", e);
        }
    }

    @Async("taskExecutor")
    public void importScopusDataCitations(String jsonFilePath) {
        importScopusDataCitationsSync(jsonFilePath);
    }

    public ImportProcessingResult importScopusDataCitationsSync(String jsonFilePath) {
        try {
            JsonNode rootNode = new ObjectMapper().readTree(new File(jsonFilePath));
            return importScopusCitationsFromRoot(
                    rootNode,
                    SOURCE_SCOPUS_JSON_BOOTSTRAP,
                    "bootstrap-citations-" + new File(jsonFilePath).getName() + "-" + System.currentTimeMillis(),
                    "bootstrap-citation-publication-",
                    "bootstrap-citation-"
            );
        } catch (IOException e) {
            logger.error("Error reading the JSON file: ", e);
            ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
            result.markError("scopus-citation-import-io-error=" + e.getMessage());
            return result;
        }
    }

    public ImportProcessingResult importUploadedScopusDataCitationsSync(String originalFilename, String batchId, byte[] jsonBytes) {
        try {
            JsonNode rootNode = new ObjectMapper().readTree(jsonBytes);
            return importScopusCitationsFromRoot(
                    rootNode,
                    SOURCE_SCOPUS_JSON_UPLOAD,
                    batchId,
                    "upload-citation-publication-",
                    "upload-citation-"
            );
        } catch (IOException e) {
            logger.error("Error reading uploaded Scopus citation JSON file: {}", originalFilename, e);
            throw new IllegalArgumentException("Failed to parse uploaded Scopus JSON file.", e);
        }
    }

    public ImportProcessingResult importUploadedPublisherCsvSync(String originalFilename, String batchId, byte[] csvBytes) {
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        long startedAtNanos = System.nanoTime();
        try (CSVReader reader = new CSVReader(new InputStreamReader(new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8))) {
            List<String[]> rows = reader.readAll();
            if (rows.isEmpty()) {
                logger.warn("Publisher CSV is empty: file={}", originalFilename);
                return result;
            }
            String[] header = rows.get(0);
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                if (header[i] != null) {
                    headerIndex.put(header[i].trim(), i);
                }
            }
            Integer sourceIdCol = headerIndex.get("sourceId");
            Integer publisherCol = headerIndex.get("Publisher");
            Integer isbnCol = headerIndex.get("ISBN");
            Integer publicationNameCol = headerIndex.get("Publication Name");
            Integer issnCol = headerIndex.get("ISSN");
            Integer aggregationTypeCol = headerIndex.get("Aggregation Type");
            if (sourceIdCol == null || publisherCol == null) {
                throw new IllegalArgumentException("Publisher CSV missing required columns sourceId / Publisher");
            }
            int rowCount = rows.size() - 1;
            logger.info("Processing {} rows from publisher CSV: file={}", rowCount, originalFilename);
            for (int i = 1; i < rows.size(); i++) {
                result.markProcessed();
                String[] row = rows.get(i);
                String sourceId = safeColumn(row, sourceIdCol);
                if (sourceId == null || sourceId.isBlank()) {
                    result.markSkipped("publisher-csv-row=" + i + " missing sourceId");
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("source_id", sourceId);
                payload.put("publicationName", safeColumn(row, publicationNameCol));
                payload.put("issn", safeColumn(row, issnCol));
                payload.put("eIssn", null);
                payload.put("isbn", normalizeBlankCsvValue(safeColumn(row, isbnCol)));
                payload.put("aggregationType", safeColumn(row, aggregationTypeCol));
                payload.put("publisher", normalizeBlankCsvValue(safeColumn(row, publisherCol)));
                ScopusImportEventIngestionService.EventIngestionOutcome outcome = importEventIngestionService.ingest(
                        ScopusImportEntityType.FORUM,
                        SOURCE_SCOPUS_PUBLISHER_CSV_UPLOAD,
                        sourceId,
                        batchId,
                        "upload-publisher-csv-" + i,
                        PAYLOAD_FORMAT_JSON_OBJECT,
                        payload
                );
                applyIngestionOutcome(result, outcome, "publisher-csv row=" + i + " sourceId=" + sourceId);
                if (result.getProcessedCount() % INGEST_HEARTBEAT == 0) {
                    logger.info("Publisher CSV ingest heartbeat: processed={} imported={} skipped={} errors={}",
                            result.getProcessedCount(), result.getImportedCount(), result.getSkippedCount(), result.getErrorCount());
                }
            }
        } catch (IOException | CsvException e) {
            logger.error("Error reading uploaded publisher CSV file: {}", originalFilename, e);
            throw new IllegalArgumentException("Failed to parse uploaded publisher CSV file.", e);
        }
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        logger.info("Publisher CSV ingest finished: file={} processed={} imported={} skipped={} errors={} elapsedMs={} sample={}",
                originalFilename,
                result.getProcessedCount(),
                result.getImportedCount(),
                result.getSkippedCount(),
                result.getErrorCount(),
                elapsedMs,
                result.getErrorsSample());
        return result;
    }

    /**
     * H66 A2: admin path-based import of the Scopus CiteScore source list (~7MB, gitignored — not bundled).
     * Seeds the canonical forum registry's identity/classification backbone. CiteScore is one row per
     * (source x ASJC sub-subject), so rows are grouped by Scopus Source ID and the ASJC codes are unioned;
     * journal-level fields (title, ISSN/eISSN, publisher, type) are taken from the source's rows. Scores
     * (CiteScore/SNIP/SJR/quartile) are intentionally skipped — no domain uses them. Emits one
     * {@code FORUM} event per source, carrying forumType + asjc through the standard FORUM ingestion path.
     */
    public ImportProcessingResult importCiteScoreCsvFromPath(String absolutePath, String batchId) {
        java.io.File source = importPathGuard.resolveWithinAllowedRoots(absolutePath);
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        long startedAtNanos = System.nanoTime();
        try (CSVReader reader = new CSVReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
            List<String[]> rows = reader.readAll();
            if (rows.isEmpty()) {
                logger.warn("CiteScore CSV is empty: path={}", absolutePath);
                return result;
            }
            String[] header = rows.get(0);
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                if (header[i] != null) {
                    headerIndex.put(header[i].trim(), i);
                }
            }
            Integer sourceIdCol = headerIndex.get("Scopus Source ID");
            Integer titleCol = headerIndex.get("Title");
            Integer typeCol = headerIndex.get("Type");
            Integer asjcCol = headerIndex.get("Scopus ASJC Code (Sub-subject Area)");
            Integer printIssnCol = headerIndex.get("Print ISSN");
            Integer eIssnCol = headerIndex.get("E-ISSN");
            Integer publisherCol = headerIndex.get("Publisher");
            if (sourceIdCol == null || titleCol == null) {
                throw new IllegalArgumentException("CiteScore CSV missing required columns 'Scopus Source ID' / 'Title'");
            }

            // Group rows by Source ID, unioning ASJC codes (preserve first-seen order).
            Map<String, CiteScoreSource> bySource = new LinkedHashMap<>();
            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);
                String sourceId = normalizeBlankCsvValue(safeColumn(row, sourceIdCol));
                if (sourceId == null) {
                    continue;
                }
                CiteScoreSource src = bySource.computeIfAbsent(sourceId, k -> new CiteScoreSource());
                src.title = firstNonBlankCsv(src.title, safeColumn(row, titleCol));
                src.printIssn = firstNonBlankCsv(src.printIssn, safeColumn(row, printIssnCol));
                src.eIssn = firstNonBlankCsv(src.eIssn, safeColumn(row, eIssnCol));
                src.publisher = firstNonBlankCsv(src.publisher, safeColumn(row, publisherCol));
                src.forumType = firstNonBlankCsv(src.forumType, mapCiteScoreType(safeColumn(row, typeCol)));
                String asjc = normalizeBlankCsvValue(safeColumn(row, asjcCol));
                if (asjc != null) {
                    src.asjc.add(asjc);
                }
            }
            logger.info("CiteScore CSV: {} rows -> {} distinct sources: path={}", rows.size() - 1, bySource.size(), absolutePath);

            for (Map.Entry<String, CiteScoreSource> entry : bySource.entrySet()) {
                result.markProcessed();
                String sourceId = entry.getKey();
                CiteScoreSource src = entry.getValue();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("source_id", sourceId);
                payload.put("publicationName", src.title);
                payload.put("issn", normalizeBlankCsvValue(src.printIssn));
                payload.put("eIssn", normalizeBlankCsvValue(src.eIssn));
                payload.put("publisher", normalizeBlankCsvValue(src.publisher));
                payload.put("forumType", src.forumType);
                payload.put("asjc", String.join(";", src.asjc));
                ScopusImportEventIngestionService.EventIngestionOutcome outcome = importEventIngestionService.ingest(
                        ScopusImportEntityType.FORUM,
                        SOURCE_SCOPUS_CITESCORE_LIST,
                        sourceId,
                        batchId,
                        "citescore-source-" + sourceId,
                        PAYLOAD_FORMAT_JSON_OBJECT,
                        payload
                );
                applyIngestionOutcome(result, outcome, "citescore sourceId=" + sourceId);
                if (result.getProcessedCount() % INGEST_HEARTBEAT == 0) {
                    logger.info("CiteScore ingest heartbeat: processed={} imported={} skipped={} errors={}",
                            result.getProcessedCount(), result.getImportedCount(), result.getSkippedCount(), result.getErrorCount());
                }
            }
        } catch (IOException | CsvException e) {
            logger.error("Error reading CiteScore CSV file: {}", absolutePath, e);
            throw new IllegalArgumentException("Failed to parse CiteScore CSV file.", e);
        }
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        logger.info("CiteScore ingest finished: path={} processed={} imported={} skipped={} errors={} elapsedMs={} sample={}",
                absolutePath,
                result.getProcessedCount(),
                result.getImportedCount(),
                result.getSkippedCount(),
                result.getErrorCount(),
                elapsedMs,
                result.getErrorsSample());
        return result;
    }

    /**
     * H66 A6 — load the Scopus Source List (`ext_list_*.xlsx`) as the authoritative serial forum backbone.
     * Emits FORUM events keyed by Scopus {@code Sourcerecord ID} (one per serial/conference source) into the
     * existing FORUM-event pipeline → `scopus.forum_facts` → canonical serial forums. Unlike CiteScore (a
     * scored subset), this is full Scopus serial coverage; it supplies forum identity (ISSN/EISSN), type,
     * publisher and ASJC. Reads the "Scopus Sources" sheet (journals/book-series/trade) and the
     * "Serial Conf. Proc. with Profile" sheet (conferences). Sheet names carry a month suffix, so they are
     * matched by prefix.
     */
    public ImportProcessingResult importSourceListXlsxFromPath(String absolutePath, String batchId) {
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        long startedAtNanos = System.nanoTime();
        DataFormatter fmt = new DataFormatter();
        // H66B M3 — stream the sheets via the POI event (SAX) reader instead of a full in-memory
        // XSSFWorkbook: the May-2026 Source List has a part exceeding POI's 100 MB byte-array ceiling, which
        // a full load trips (RecordFormatException). SAX reads each sheet incrementally (perf invariant:
        // stream large xlsx). Sheet names carry a month suffix, so match by prefix.
        boolean matchedAnySheet = false;
        try (OPCPackage pkg = OPCPackage.open(new File(absolutePath), PackageAccess.READ)) {
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    String sheetName = sheets.getSheetName();
                    Boolean isConference = sheetName.startsWith("Scopus Sources") ? Boolean.FALSE
                            : sheetName.startsWith("Serial Conf") ? Boolean.TRUE
                            : null;
                    if (isConference == null) {
                        continue;
                    }
                    matchedAnySheet = true;
                    parseSourceListSheet(sheetStream, styles, sharedStrings, fmt, isConference, sheetName, batchId, result);
                }
            }
        } catch (IOException | org.apache.poi.openxml4j.exceptions.OpenXML4JException
                 | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException
                 | RuntimeException e) {
            // RuntimeException covers POI's unchecked bad-file errors (InvalidOperationException for a
            // missing/unreadable package, NotOfficeXmlFileException, etc.) — all mean "not a usable xlsx".
            logger.error("Error reading Scopus Source List xlsx: {}", absolutePath, e);
            throw new IllegalArgumentException("Failed to parse Scopus Source List xlsx.", e);
        }
        if (!matchedAnySheet) {
            throw new IllegalArgumentException(
                    "Scopus Source List missing both 'Scopus Sources' and 'Serial Conf' sheets");
        }
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        logger.info("Scopus Source List ingest finished: path={} processed={} imported={} updated={} skipped={} errors={} elapsedMs={} sample={}",
                absolutePath, result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                result.getSkippedCount(), result.getErrorCount(), elapsedMs, result.getErrorsSample());
        return result;
    }

    /**
     * H66B M7 — load the Scopus Books List (`Scopus_Books_list_*.xlsx`, ~475k rows) as the book registry.
     * Books are a distinct entity from forums: upserts {@code scholardex.book_facts} keyed by Scopus Source
     * ID (TITLE / PRINT ISBN / ELECTRONIC ISBN / PUBLISHER / PUBLICATION YEAR / ASJC / SCOPUS ID). Streamed
     * via the POI SAX reader (same pattern as the Source List) and flushed in batches during the parse so the
     * full 475k rows never materialize at once.
     */
    public ImportProcessingResult importBookListXlsxFromPath(String absolutePath, String batchId, String asOf) {
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        long startedAtNanos = System.nanoTime();
        DataFormatter fmt = new DataFormatter();
        boolean parsedAnySheet = false;
        try (OPCPackage pkg = OPCPackage.open(new File(absolutePath), PackageAccess.READ)) {
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    if (!sheets.getSheetName().startsWith("Scopus_Books")) {
                        continue;
                    }
                    parsedAnySheet = true;
                    BookSheetHandler handler = new BookSheetHandler(batchId, asOf, result);
                    XMLReader xmlReader = XMLHelper.newXMLReader();
                    xmlReader.setContentHandler(new XSSFSheetXMLHandler(styles, sharedStrings, handler, fmt, false));
                    xmlReader.parse(new InputSource(sheetStream));
                    handler.flushRemaining();
                }
            }
        } catch (IOException | org.apache.poi.openxml4j.exceptions.OpenXML4JException
                 | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException
                 | RuntimeException e) {
            logger.error("Error reading Scopus Books List xlsx: {}", absolutePath, e);
            throw new IllegalArgumentException("Failed to parse Scopus Books List xlsx.", e);
        }
        if (!parsedAnySheet) {
            throw new IllegalArgumentException("Scopus Books List missing the 'Scopus_Books' sheet");
        }
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        logger.info("Scopus Books List ingest finished: path={} processed={} imported={} skipped={} errors={} elapsedMs={}",
                absolutePath, result.getProcessedCount(), result.getImportedCount(), result.getSkippedCount(),
                result.getErrorCount(), elapsedMs);
        return result;
    }

    /**
     * SAX row handler for the Scopus_Books sheet: first non-empty row defines the header, each data row builds
     * a {@link ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact} keyed by Scopus Source ID,
     * buffered and saved in batches (the sheet is too large to hold in memory).
     */
    private final class BookSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final String batchId;
        private final String asOf;
        private final ImportProcessingResult result;
        private final Map<String, Integer> headerIdx = new HashMap<>();
        private final Map<Integer, String> rowCells = new HashMap<>();
        private final List<ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact> buffer = new ArrayList<>();
        private final java.time.Instant now = java.time.Instant.now();
        private boolean headerParsed = false;
        private boolean currentRowIsHeader;

        BookSheetHandler(String batchId, String asOf, ImportProcessingResult result) {
            this.batchId = batchId;
            this.asOf = asOf;
            this.result = result;
        }

        @Override
        public void startRow(int rowNum) {
            rowCells.clear();
            currentRowIsHeader = !headerParsed;
        }

        @Override
        public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            if (cellReference == null || formattedValue == null) {
                return;
            }
            String value = formattedValue.trim();
            if (!value.isEmpty()) {
                rowCells.put((int) new CellReference(cellReference).getCol(), value);
            }
        }

        @Override
        public void endRow(int rowNum) {
            if (currentRowIsHeader) {
                if (rowCells.isEmpty()) {
                    return;
                }
                for (Map.Entry<Integer, String> e : rowCells.entrySet()) {
                    headerIdx.put(e.getValue(), e.getKey());
                }
                headerParsed = true;
                if (!headerIdx.containsKey("SCOPUS ID")) {
                    logger.warn("Scopus Books List sheet has no 'SCOPUS ID' column; skipping");
                }
                return;
            }
            if (!headerIdx.containsKey("SCOPUS ID")) {
                return;
            }
            String scopusId = cellValue(rowCells, headerIdx.get("SCOPUS ID"));
            if (scopusId == null) {
                return;
            }
            result.markProcessed();
            var book = new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact();
            book.setId(scopusId);
            book.setScopusId(scopusId);
            book.setTitle(cellValue(rowCells, headerIdx.get("TITLE")));
            book.setPrintIsbn(cellValue(rowCells, headerIdx.get("PRINT ISBN")));
            book.setElectronicIsbn(cellValue(rowCells, headerIdx.get("ELECTRONIC ISBN")));
            book.setPublisher(cellValue(rowCells, headerIdx.get("PUBLISHER")));
            book.setPublicationYear(parseYear(cellValue(rowCells, headerIdx.get("PUBLICATION YEAR"))));
            book.setAsjc(splitAsjcList(cellValue(rowCells, headerIdx.get("ASJC"))));
            book.setAsOf(asOf);
            book.setSource(SOURCE_SCOPUS_BOOK_LIST);
            book.setSourceBatchId(batchId);
            book.setCreatedAt(now);
            book.setUpdatedAt(now);
            buffer.add(book);
            if (buffer.size() >= BOOK_FLUSH_CHUNK) {
                flush();
            }
        }

        private void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            bookFactRepository.saveAll(new ArrayList<>(buffer));
            buffer.forEach(b -> result.markImported());
            if (result.getProcessedCount() % BOOK_HEARTBEAT == 0 || result.getImportedCount() % BOOK_HEARTBEAT == 0) {
                logger.info("Scopus Books List ingest heartbeat: processed={} imported={}",
                        result.getProcessedCount(), result.getImportedCount());
            }
            buffer.clear();
        }

        void flushRemaining() {
            flush();
        }
    }

    private static Integer parseYear(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(digits.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> splitAsjcList(String raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String token : raw.split("[;,]")) {
            String t = token.replaceAll("\\s+", "");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private void parseSourceListSheet(InputStream sheetStream, StylesTable styles, SharedStrings sharedStrings,
                                      DataFormatter fmt, boolean isConference, String sheetName, String batchId,
                                      ImportProcessingResult result)
            throws IOException, org.xml.sax.SAXException, javax.xml.parsers.ParserConfigurationException {
        XMLReader xmlReader = XMLHelper.newXMLReader();
        xmlReader.setContentHandler(new XSSFSheetXMLHandler(styles, sharedStrings,
                new SourceListSheetHandler(isConference, sheetName, batchId, result), fmt, false));
        xmlReader.parse(new InputSource(sheetStream));
    }

    /**
     * SAX row handler for one Source List sheet: the first non-empty row defines the header (column name →
     * index), subsequent rows emit a FORUM event keyed by Sourcerecord ID. Blank cells never fire a callback,
     * so cells are collected by column index (parsed from the cell reference) and looked up by header name —
     * positionally sparse rows resolve to null exactly as the prior RETURN_BLANK_AS_NULL path did.
     */
    private final class SourceListSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final boolean isConference;
        private final String sheetName;
        private final String batchId;
        private final ImportProcessingResult result;
        private final Map<String, Integer> headerIdx = new HashMap<>();
        private final Map<Integer, String> rowCells = new HashMap<>();
        private boolean headerParsed = false;
        private boolean currentRowIsHeader;

        SourceListSheetHandler(boolean isConference, String sheetName, String batchId, ImportProcessingResult result) {
            this.isConference = isConference;
            this.sheetName = sheetName;
            this.batchId = batchId;
            this.result = result;
        }

        @Override
        public void startRow(int rowNum) {
            rowCells.clear();
            currentRowIsHeader = !headerParsed;
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (cellReference == null || formattedValue == null) {
                return;
            }
            String value = formattedValue.trim();
            if (value.isEmpty()) {
                return;
            }
            rowCells.put((int) new CellReference(cellReference).getCol(), value);
        }

        @Override
        public void endRow(int rowNum) {
            if (currentRowIsHeader) {
                if (rowCells.isEmpty()) {
                    return; // leading blank row before the header
                }
                for (Map.Entry<Integer, String> e : rowCells.entrySet()) {
                    headerIdx.put(e.getValue(), e.getKey());
                }
                headerParsed = true;
                if (!headerIdx.containsKey("Sourcerecord ID")) {
                    logger.warn("Scopus Source List sheet '{}' has no 'Sourcerecord ID' column; skipping", sheetName);
                }
                return;
            }
            if (!headerIdx.containsKey("Sourcerecord ID")) {
                return;
            }
            ingestSourceListRow(headerIdx, rowCells, isConference, batchId, result);
        }
    }

    private void ingestSourceListRow(Map<String, Integer> idx, Map<Integer, String> cells, boolean isConference,
                                     String batchId, ImportProcessingResult result) {
        String sourceId = cellValue(cells, idx.get("Sourcerecord ID"));
        if (sourceId == null) {
            return;
        }
        result.markProcessed();
        String forumType = isConference ? "conference" : mapSourceListType(cellValue(cells, idx.get("Source Type")));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_id", sourceId);
        payload.put("publicationName", cellValue(cells, idx.get("Source Title")));
        payload.put("issn", normalizeSourceListIssn(cellValue(cells, idx.get("ISSN"))));
        payload.put("eIssn", normalizeSourceListIssn(cellValue(cells, idx.get("EISSN"))));
        payload.put("publisher", cellValue(cells, idx.get("Publisher")));
        payload.put("forumType", forumType);
        payload.put("asjc", normalizeSourceListAsjc(cellValue(cells, idx.get("All Science Journal Classification Codes (ASJC)"))));
        ScopusImportEventIngestionService.EventIngestionOutcome outcome = importEventIngestionService.ingest(
                ScopusImportEntityType.FORUM,
                SOURCE_SCOPUS_SOURCE_LIST,
                sourceId,
                batchId,
                "sourcelist-" + sourceId,
                PAYLOAD_FORMAT_JSON_OBJECT,
                payload
        );
        applyIngestionOutcome(result, outcome, "sourcelist sourceId=" + sourceId);
        if (result.getProcessedCount() % INGEST_HEARTBEAT == 0) {
            logger.info("Scopus Source List ingest heartbeat: processed={} imported={} skipped={} errors={}",
                    result.getProcessedCount(), result.getImportedCount(), result.getSkippedCount(), result.getErrorCount());
        }
    }

    /** Cell value for a header-resolved column index (already trimmed, blanks absent) — null when missing. */
    private static String cellValue(Map<Integer, String> cells, Integer col) {
        return col == null ? null : cells.get(col);
    }

    /** Map the Scopus Source List "Source Type" words to the canonical forumType vocabulary. */
    private static String mapSourceListType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "journal" -> "journal";
            case "book series" -> "book-series";
            case "conference proceeding", "conference proceedings" -> "conference";
            case "trade journal" -> "trade";
            default -> null;
        };
    }

    /** ISSN cells are 8-char (often stored numeric, losing leading zeros) — strip non-ISSN chars and pad to 8. */
    private static String normalizeSourceListIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
        if (compact.isEmpty()) {
            return null;
        }
        if (compact.length() < 8) {
            compact = "0".repeat(8 - compact.length()) + compact; // recover leading zeros lost to numeric storage
        }
        return compact;
    }

    /** Normalize ASJC code list to the semicolon-separated form the fact builder splits. */
    private static String normalizeSourceListAsjc(String raw) {
        if (raw == null) {
            return null;
        }
        String norm = raw.replaceAll("[;,]+", ";").replaceAll("\\s+", "").replaceAll("^;|;$", "");
        return norm.isEmpty() ? null : norm;
    }

    /** Map the CiteScore single-letter Type to the canonical forumType vocabulary. */
    private static String mapCiteScoreType(String rawType) {
        String t = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "j" -> "journal";
            case "k" -> "book-series";
            case "p" -> "conference";
            case "d" -> "trade";
            default -> null;
        };
    }

    private static String firstNonBlankCsv(String existing, String incoming) {
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String normalized = normalizeBlankCsvValue(incoming);
        return normalized != null ? normalized : existing;
    }

    /** Mutable per-source accumulator for the CiteScore row grouping. */
    private static final class CiteScoreSource {
        private String title;
        private String printIssn;
        private String eIssn;
        private String publisher;
        private String forumType;
        private final LinkedHashSet<String> asjc = new LinkedHashSet<>();
    }

    private static String safeColumn(String[] row, Integer col) {
        if (col == null || col < 0 || col >= row.length) {
            return null;
        }
        return row[col];
    }

    private static String normalizeBlankCsvValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null-".equalsIgnoreCase(trimmed) || "Not found".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    public String createUploadBatchId(String originalFilename) {
        return "upload-" + normalizeBatchFileName(originalFilename) + "-" + System.currentTimeMillis();
    }

    private ImportProcessingResult importScopusDataFromRoot(
            JsonNode rootNode,
            long count,
            String source,
            String batchId,
            String correlationPrefix
    ) {
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        int dataSize = readDataSize(rootNode);
        logger.info("Processing starting at {} of {} publications from Scopus JSON payload. source={}", count, dataSize, source);
        long startedAtNanos = System.nanoTime();
        for (int i = (int) count; i < dataSize; i++) {
            result.markProcessed();
            try {
                String eid = readRequiredIndexedText(rootNode, "eid", i, "scopus-import-index-" + i);
                Map<String, Object> payload = extractIndexedPublicationPayload(rootNode, i);
                ScopusImportEventIngestionService.EventIngestionOutcome outcome = importEventIngestionService.ingest(
                        ScopusImportEntityType.PUBLICATION,
                        source,
                        eid,
                        batchId,
                        correlationPrefix + i,
                        PAYLOAD_FORMAT_JSON_OBJECT,
                        payload
                );
                applyIngestionOutcome(result, outcome, "publication index=" + i + ", eid=" + eid);
            } catch (IntegrationException ex) {
                result.markSkipped("index=" + i + ", code=" + ex.getErrorCode() + ", msg=" + ex.getMessage());
            } catch (RuntimeException ex) {
                result.markSkipped("index=" + i + ", code=" + IntegrationErrorCode.PERSISTENCE_ERROR + ", msg=" + ex.getMessage());
            }
            if (dataSize >= 10 && i % (dataSize / 10) == 0) {
                logger.info("Processed {}% publications.", (i * 100.0) / dataSize);
            }
            if (result.getProcessedCount() % INGEST_HEARTBEAT == 0) {
                long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
                double rate = elapsedMs == 0 ? 0.0 : (result.getProcessedCount() * 1000.0) / elapsedMs;
                logger.info("Scopus publication ingest progress: processed={} imported={} skipped={} errors={} elapsedMs={} ratePerSec={} source={}",
                        result.getProcessedCount(),
                        result.getImportedCount(),
                        result.getSkippedCount(),
                        result.getErrorCount(),
                        elapsedMs,
                        String.format(Locale.ROOT, "%.2f", rate),
                        source);
            }
        }
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        logger.info("Scopus publication import finished: source={} processed={} imported={} updated={} skipped={} errors={} sample={}",
                source,
                result.getProcessedCount(),
                result.getImportedCount(),
                result.getUpdatedCount(),
                result.getSkippedCount(),
                result.getErrorCount(),
                result.getErrorsSample());
        logger.info("Scopus publication ingest timings: source={} elapsedMs={} ratePerSec={}",
                source,
                elapsedMs,
                String.format(Locale.ROOT, "%.2f", elapsedMs == 0 ? 0.0 : (result.getProcessedCount() * 1000.0) / elapsedMs));
        return result;
    }

    private ImportProcessingResult importScopusCitationsFromRoot(
            JsonNode rootNode,
            String source,
            String batchId,
            String publicationCorrelationPrefix,
            String citationCorrelationPrefix
    ) {
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        int dataSize = readDataSize(rootNode);
        logger.info("Processing citations from {} publications from Scopus JSON payload. source={}", dataSize, source);
        long startedAtNanos = System.nanoTime();
        Map<String, List<JsonNode>> citations = extractCitationsFromJson(rootNode, dataSize);
        processCitations(citations, batchId, result, startedAtNanos, source, publicationCorrelationPrefix, citationCorrelationPrefix);
        logger.info("Scopus citation import finished: source={} processed={} imported={} skipped={} errors={} sample={}",
                source,
                result.getProcessedCount(),
                result.getImportedCount(),
                result.getSkippedCount(),
                result.getErrorCount(),
                result.getErrorsSample());
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        logger.info("Scopus citation ingest timings: source={} elapsedMs={} ratePerSec={}",
                source,
                elapsedMs,
                String.format(Locale.ROOT, "%.2f", elapsedMs == 0 ? 0.0 : (result.getProcessedCount() * 1000.0) / elapsedMs));
        return result;
    }

    private int readDataSize(JsonNode rootNode) {
        JsonNode eidNode = rootNode.get("eid");
        if (eidNode == null || (!eidNode.isArray() && !eidNode.isObject())) {
            throw new IllegalArgumentException("Uploaded Scopus JSON must contain an 'eid' array or row-indexed object.");
        }
        return eidNode.size();
    }

    private Map<String, List<JsonNode>> extractCitationsFromJson(JsonNode rootNode, int dataSize) {
        Map<String, List<JsonNode>> citations = new HashMap<>();
        for (int i = 0; i < dataSize; i++) {
            String id = readOptionalIndexedText(rootNode, "eid", i);
            if (id.isBlank()) {
                continue;
            }
            JsonNode citingArticles = rootNode.path("citing articles").path(String.valueOf(i));
            if (citingArticles != null && citingArticles.getNodeType() != JsonNodeType.NUMBER) {
                citations.putIfAbsent(id, new ArrayList<>());
                for (JsonNode article : citingArticles) {
                    citations.get(id).add(article);
                }
            } else {
                logger.debug("No citations for {}", id);
            }
        }
        return citations;
    }

    private void processCitations(
            Map<String, List<JsonNode>> citations,
            String batchId,
            ImportProcessingResult result,
            long startedAtNanos,
            String source,
            String publicationCorrelationPrefix,
            String citationCorrelationPrefix
    ) {
        List<ScopusImportEventIngestionService.BatchIngestionItem> pendingPublicationEvents = new ArrayList<>(CITATION_INGEST_BATCH_SIZE);
        List<ScopusImportEventIngestionService.BatchIngestionItem> pendingCitationEvents = new ArrayList<>(CITATION_INGEST_BATCH_SIZE);
        long cumulativePublicationSerializeMs = 0L;
        long cumulativePublicationDbMs = 0L;
        long cumulativeCitationSerializeMs = 0L;
        long cumulativeCitationDbMs = 0L;
        int sequence = 0;

        for (Map.Entry<String, List<JsonNode>> entry : citations.entrySet()) {
            String citedEid = entry.getKey();
            List<JsonNode> citationNodes = entry.getValue();
            if (citationNodes == null) {
                continue;
            }
            for (JsonNode citationNode : citationNodes) {
                result.markProcessed();
                sequence++;
                try {
                    String citingEid = readRequiredText(citationNode, "eid", "citation-citing-eid");
                    pendingPublicationEvents.add(new ScopusImportEventIngestionService.BatchIngestionItem(
                            citingEid,
                            publicationCorrelationPrefix + citedEid + "-" + sequence,
                            citationNode
                    ));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("citedEid", citedEid);
                    payload.put("citingEid", citingEid);
                    pendingCitationEvents.add(new ScopusImportEventIngestionService.BatchIngestionItem(
                            citedEid + "->" + citingEid,
                            citationCorrelationPrefix + citedEid + "-" + sequence,
                            payload
                    ));
                } catch (IntegrationException ex) {
                    result.markSkipped("citedEid=" + citedEid + ", code=" + ex.getErrorCode() + ", msg=" + ex.getMessage());
                } catch (RuntimeException ex) {
                    result.markSkipped("citedEid=" + citedEid + ", code=" + IntegrationErrorCode.PERSISTENCE_ERROR + ", msg=" + ex.getMessage());
                }

                if (pendingCitationEvents.size() >= CITATION_INGEST_BATCH_SIZE) {
                    CitationBatchOutcome batchOutcome = flushCitationBatch(pendingPublicationEvents, pendingCitationEvents, batchId, source);
                    applyBatchOutcome(result, batchOutcome.citationOutcome());
                    cumulativePublicationSerializeMs += batchOutcome.publicationOutcome().serializeMs();
                    cumulativePublicationDbMs += batchOutcome.publicationOutcome().dbInsertEventMs();
                    cumulativeCitationSerializeMs += batchOutcome.citationOutcome().serializeMs();
                    cumulativeCitationDbMs += batchOutcome.citationOutcome().dbInsertEventMs();
                    pendingPublicationEvents.clear();
                    pendingCitationEvents.clear();
                }

                if (result.getProcessedCount() % INGEST_HEARTBEAT == 0) {
                    long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
                    double rate = elapsedMs == 0 ? 0.0 : (result.getProcessedCount() * 1000.0) / elapsedMs;
                    logger.info("Scopus citation ingest progress: processed={} imported={} skipped={} errors={} elapsedMs={} ratePerSec={} timingsMs[publicationSerialize={}, citationSerialize={}, publicationEventInsert={}, citationEventInsert={}, total={}]",
                            result.getProcessedCount(),
                            result.getImportedCount(),
                            result.getSkippedCount(),
                            result.getErrorCount(),
                            elapsedMs,
                            String.format(Locale.ROOT, "%.2f", rate),
                            cumulativePublicationSerializeMs,
                            cumulativeCitationSerializeMs,
                            cumulativePublicationDbMs,
                            cumulativeCitationDbMs,
                            cumulativePublicationSerializeMs + cumulativeCitationSerializeMs + cumulativePublicationDbMs + cumulativeCitationDbMs);
                }
            }
        }
        if (!pendingCitationEvents.isEmpty()) {
            CitationBatchOutcome batchOutcome = flushCitationBatch(pendingPublicationEvents, pendingCitationEvents, batchId, source);
            applyBatchOutcome(result, batchOutcome.citationOutcome());
        }
    }

    private CitationBatchOutcome flushCitationBatch(
            List<ScopusImportEventIngestionService.BatchIngestionItem> pendingPublicationEvents,
            List<ScopusImportEventIngestionService.BatchIngestionItem> pendingCitationEvents,
            String batchId,
            String source
    ) {
        ScopusImportEventIngestionService.BatchIngestionOutcome publicationOutcome = importEventIngestionService.ingestBatch(
                ScopusImportEntityType.PUBLICATION,
                source,
                batchId,
                PAYLOAD_FORMAT_JSON_OBJECT,
                pendingPublicationEvents
        );
        ScopusImportEventIngestionService.BatchIngestionOutcome citationOutcome = importEventIngestionService.ingestBatch(
                ScopusImportEntityType.CITATION,
                source,
                batchId,
                PAYLOAD_FORMAT_JSON_OBJECT,
                pendingCitationEvents
        );
        return new CitationBatchOutcome(publicationOutcome, citationOutcome);
    }

    private void applyBatchOutcome(
            ImportProcessingResult result,
            ScopusImportEventIngestionService.BatchIngestionOutcome batchOutcome
    ) {
        for (int i = 0; i < batchOutcome.imported(); i++) {
            result.markImported();
        }
        for (int i = 0; i < batchOutcome.skipped(); i++) {
            result.markSkipped("citation-batch-duplicate");
        }
        for (int i = 0; i < batchOutcome.errors(); i++) {
            result.markError("citation-batch-error");
        }
    }

    private record CitationBatchOutcome(
            ScopusImportEventIngestionService.BatchIngestionOutcome publicationOutcome,
            ScopusImportEventIngestionService.BatchIngestionOutcome citationOutcome
    ) {
    }

    private void applyIngestionOutcome(ImportProcessingResult result,
                                       ScopusImportEventIngestionService.EventIngestionOutcome outcome,
                                       String context) {
        if (outcome.error()) {
            result.markError(context + ", msg=" + outcome.message());
            return;
        }
        if (outcome.imported()) {
            result.markImported();
            return;
        }
        result.markSkipped(context + ", reason=duplicate");
    }

    private Map<String, Object> extractIndexedPublicationPayload(JsonNode rootNode, int i) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eid", readOptionalIndexedText(rootNode, "eid", i));
        payload.put("doi", readOptionalIndexedText(rootNode, "doi", i));
        payload.put("pii", readOptionalIndexedText(rootNode, "pii", i));
        payload.put("pubmed_id", readOptionalIndexedText(rootNode, "pubmed_id", i));
        payload.put("title", readOptionalIndexedText(rootNode, "title", i));
        payload.put("subtype", readOptionalIndexedText(rootNode, "subtype", i));
        payload.put("subtypeDescription", readOptionalIndexedText(rootNode, "subtypeDescription", i));
        payload.put("creator", readOptionalIndexedText(rootNode, "creator", i));
        payload.put("author_count", readIndexedInt(rootNode, "author_count", i));
        payload.put("description", readOptionalIndexedText(rootNode, "description", i));
        payload.put("authkeywords", readOptionalIndexedText(rootNode, "authkeywords", i));
        payload.put("correspondingAuthors", readOptionalIndexedText(rootNode, "correspondingAuthors", i));
        payload.put("citedby_count", readIndexedInt(rootNode, "citedby_count", i));
        payload.put("openaccess", readIndexedInt(rootNode, "openaccess", i));
        payload.put("freetoread", readOptionalIndexedText(rootNode, "freetoread", i));
        payload.put("freetoreadLabel", readOptionalIndexedText(rootNode, "freetoreadLabel", i));
        payload.put("article_number", readOptionalIndexedText(rootNode, "article_number", i));
        payload.put("pageRange", readOptionalIndexedText(rootNode, "pageRange", i));
        payload.put("coverDate", readOptionalIndexedText(rootNode, "coverDate", i));
        payload.put("coverDisplayDate", readOptionalIndexedText(rootNode, "coverDisplayDate", i));
        payload.put("volume", readOptionalIndexedText(rootNode, "volume", i));
        payload.put("issueIdentifier", readOptionalIndexedText(rootNode, "issueIdentifier", i));
        payload.put("afid", readOptionalIndexedText(rootNode, "afid", i));
        payload.put("affilname", readOptionalIndexedText(rootNode, "affilname", i));
        payload.put("affiliation_city", readOptionalIndexedText(rootNode, "affiliation_city", i));
        payload.put("affiliation_country", readOptionalIndexedText(rootNode, "affiliation_country", i));
        payload.put("author_ids", readOptionalIndexedText(rootNode, "author_ids", i));
        payload.put("author_names", readOptionalIndexedText(rootNode, "author_names", i));
        payload.put("author_afids", readOptionalIndexedText(rootNode, "author_afids", i));
        payload.put("source_id", readOptionalIndexedText(rootNode, "source_id", i));
        payload.put("publicationName", readOptionalIndexedText(rootNode, "publicationName", i));
        payload.put("issn", readOptionalIndexedText(rootNode, "issn", i));
        payload.put("eIssn", readOptionalIndexedText(rootNode, "eIssn", i));
        payload.put("isbn", readOptionalIndexedText(rootNode, "isbn", i));
        payload.put("aggregationType", readOptionalIndexedText(rootNode, "aggregationType", i));
        payload.put("publisher", readOptionalIndexedText(rootNode, "publisher", i));
        payload.put("fund_acr", readOptionalIndexedText(rootNode, "fund_acr", i));
        payload.put("fund_no", readOptionalIndexedText(rootNode, "fund_no", i));
        payload.put("fund_sponsor", readOptionalIndexedText(rootNode, "fund_sponsor", i));
        return payload;
    }

    private String readRequiredText(JsonNode node, String field, Integer index, String contextId) {
        JsonNode fieldNode = resolveFieldNode(node, field, index);
        String location = index == null ? "(" + contextId + ")" : "at index " + index + " (" + contextId + ")";
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            throw new IntegrationException(
                    IntegrationErrorCode.VALIDATION_ERROR,
                    false,
                    "Missing required field '" + field + "' " + location
            );
        }
        String value = fieldNode.asText("").trim();
        if (value.isBlank()) {
            throw new IntegrationException(
                    IntegrationErrorCode.VALIDATION_ERROR,
                    false,
                    "Blank required field '" + field + "' " + location
            );
        }
        return value;
    }

    private String readRequiredIndexedText(JsonNode node, String field, int index, String contextId) {
        return readRequiredText(node, field, index, contextId);
    }

    private String readRequiredText(JsonNode node, String field, String contextId) {
        return readRequiredText(node, field, null, contextId);
    }

    private String readOptionalText(JsonNode node, String field, Integer index) {
        JsonNode fieldNode = resolveFieldNode(node, field, index);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            return "";
        }
        return normalizeOptionalValue(fieldNode.asText(""));
    }

    private String readOptionalIndexedText(JsonNode node, String field, int index) {
        return readOptionalText(node, field, index);
    }

    private String readOptionalText(JsonNode node, String field) {
        return readOptionalText(node, field, null);
    }

    private int readInt(JsonNode node, String field, Integer index) {
        JsonNode fieldNode = resolveFieldNode(node, field, index);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            return 0;
        }
        if (fieldNode.canConvertToInt()) {
            return fieldNode.asInt();
        }
        try {
            return Integer.parseInt(fieldNode.asText("").trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private int readIndexedInt(JsonNode node, String field, int index) {
        return readInt(node, field, index);
    }

    private int readInt(JsonNode node, String field) {
        return readInt(node, field, null);
    }

    private JsonNode resolveFieldNode(JsonNode node, String field, Integer index) {
        JsonNode fieldNode = node.path(field);
        if (index == null) {
            return fieldNode;
        }
        if (fieldNode.isArray()) {
            return fieldNode.path(index);
        }
        return fieldNode.path(String.valueOf(index));
    }

    private String normalizeOptionalValue(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.equalsIgnoreCase("null") || normalized.equalsIgnoreCase("n/a")) {
            return "";
        }
        return normalized;
    }

    private String normalizeBatchFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unknown";
        }
        return originalFilename.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
