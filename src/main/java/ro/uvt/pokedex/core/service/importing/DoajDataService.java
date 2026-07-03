package ro.uvt.pokedex.core.service.importing;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.doaj.DoajJournalFact;
import ro.uvt.pokedex.core.repository.doaj.DoajJournalFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * H66 A4 — load the DOAJ open-access snapshot CSV into {@code doaj.journal_facts}.
 *
 * <p>DOAJ has no native forum id, so this importer does NOT touch the canonical forum registry (it would
 * otherwise mint thousands of ISSN-only forums for OA journals absent from Scopus/WoS). It stores one fact
 * per journal keyed by normalized ISSN; the projection ({@code ScholardexProjectionBuilderService}) matches
 * those ISSNs to existing forums and emits {@code database='DOAJ'} membership rows. Match-only by design.
 */
@Service
@RequiredArgsConstructor
public class DoajDataService {

    private static final Logger logger = LoggerFactory.getLogger(DoajDataService.class);
    private static final int DEFAULT_ERROR_SAMPLE_SIZE = 20;
    private static final String SOURCE_DOAJ = "DOAJ";
    private static final Pattern ISSN_NON_ALNUM = Pattern.compile("[^0-9Xx]");

    private static final String COL_TITLE = "Journal title";
    private static final String COL_PRINT_ISSN = "Journal ISSN (print version)";
    private static final String COL_EISSN = "Journal EISSN (online version)";
    private static final String COL_APC = "APC"; // H79 — Yes/No: does the OA journal charge an APC?

    private final DoajJournalFactRepository doajJournalFactRepository;

    /**
     * Parse the DOAJ CSV at {@code absolutePath} and upsert one {@link DoajJournalFact} per journal.
     *
     * @param asOf snapshot stamp surfaced as {@code membership_view.as_of} (e.g. the dump year); may be null.
     */
    public ImportProcessingResult importDoajCsvFromPath(String absolutePath, String batchId, String asOf) {
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        Instant now = Instant.now();
        try (CSVReader reader = new CSVReader(new InputStreamReader(new FileInputStream(absolutePath), StandardCharsets.UTF_8))) {
            List<String[]> rows = reader.readAll();
            if (rows.isEmpty()) {
                logger.warn("DOAJ CSV is empty: path={}", absolutePath);
                return result;
            }
            String[] header = rows.get(0);
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                if (header[i] != null) {
                    headerIndex.put(header[i].trim(), i);
                }
            }
            Integer titleCol = headerIndex.get(COL_TITLE);
            Integer printIssnCol = headerIndex.get(COL_PRINT_ISSN);
            Integer eIssnCol = headerIndex.get(COL_EISSN);
            Integer apcCol = headerIndex.get(COL_APC);
            if (printIssnCol == null && eIssnCol == null) {
                throw new IllegalArgumentException(
                        "DOAJ CSV missing both ISSN columns ('" + COL_PRINT_ISSN + "' / '" + COL_EISSN + "')");
            }

            // De-duplicate within the file by stable id (a journal listed twice collapses to one fact).
            Map<String, DoajJournalFact> byId = new LinkedHashMap<>();
            for (int i = 1; i < rows.size(); i++) {
                result.markProcessed();
                String[] row = rows.get(i);
                String issn = normalizeIssn(safeColumn(row, printIssnCol));
                String eIssn = normalizeIssn(safeColumn(row, eIssnCol));
                String id = issn != null ? issn : eIssn;
                if (id == null) {
                    result.markSkipped("doaj-row-without-issn line=" + (i + 1));
                    continue;
                }
                DoajJournalFact fact = byId.computeIfAbsent("doaj_" + id, k -> {
                    DoajJournalFact f = new DoajJournalFact();
                    f.setId(k);
                    return f;
                });
                if (fact.getIssn() == null) {
                    fact.setIssn(issn);
                }
                if (fact.getEIssn() == null) {
                    fact.setEIssn(eIssn);
                }
                if (fact.getTitle() == null) {
                    fact.setTitle(safeColumn(row, titleCol));
                }
                if (fact.getApc() == null) {
                    fact.setApc(parseApc(safeColumn(row, apcCol)));
                }
                fact.setAsOf(asOf);
                fact.setSource(SOURCE_DOAJ);
                fact.setSourceBatchId(batchId);
            }

            // Blank-tolerant upsert against what's already stored (preserve created timestamps, refresh content).
            Map<String, DoajJournalFact> existing = new HashMap<>();
            for (DoajJournalFact stored : doajJournalFactRepository.findByIdIn(byId.keySet())) {
                existing.put(stored.getId(), stored);
            }
            for (DoajJournalFact incoming : byId.values()) {
                DoajJournalFact stored = existing.get(incoming.getId());
                incoming.setCreatedAt(stored != null && stored.getCreatedAt() != null ? stored.getCreatedAt() : now);
                incoming.setUpdatedAt(now);
                if (stored == null) {
                    result.markImported();
                } else {
                    result.markUpdated();
                }
            }
            doajJournalFactRepository.saveAll(new java.util.ArrayList<>(byId.values()));
            logger.info("DOAJ import complete: path={} rows={} journals={} batchId={} asOf={}",
                    absolutePath, rows.size() - 1, byId.size(), batchId, asOf);
        } catch (IOException | CsvException ex) {
            logger.error("DOAJ import failed: path={}", absolutePath, ex);
            result.markError("doaj-import-failed: " + ex.getMessage());
        }
        return result;
    }

    /** DOAJ "APC" column → tri-state: "Yes"→true, "No"→false, blank/absent→null. */
    private static Boolean parseApc(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("yes") || v.equals("true")) {
            return Boolean.TRUE;
        }
        if (v.equals("no") || v.equals("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String safeColumn(String[] row, Integer index) {
        if (index == null || index < 0 || index >= row.length) {
            return null;
        }
        String value = row[index];
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Compact, upper-cased ISSN (hyphens/spaces stripped); null unless exactly 8 ISSN chars. */
    private static String normalizeIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = ISSN_NON_ALNUM.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        return compact.length() == 8 ? compact : null;
    }
}
