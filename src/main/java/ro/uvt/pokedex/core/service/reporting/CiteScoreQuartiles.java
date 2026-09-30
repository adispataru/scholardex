package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H129 — the CiteScore quartile of a Scopus source per list year, for Anexa 5.3: "cea mai bună clasare din
 * anul în care a fost publicat articolul" over the subject areas the source is ranked in. Read from the
 * yearly CiteScore exports of the Scopus Sources page ({@code CiteScore <year> per <month>.csv}, one row per
 * source × subject area, columns "Scopus Source ID" and "Quartile"), found in the configured directory.
 * <p>
 * A year that has no file takes the nearest list that exists; the answer says which list it came from, so
 * the sheet can tell. Files are read once, on first use.
 */
@Component
public class CiteScoreQuartiles {

    private static final Logger log = LoggerFactory.getLogger(CiteScoreQuartiles.class);
    private static final Pattern FILE = Pattern.compile("^CiteScore (\\d{4})\\b.*\\.csv$", Pattern.CASE_INSENSITIVE);

    /** The best quartile of a source in a list, and the year of the list it came from. */
    public record Placement(int quartile, int listYear) {
    }

    private final Path directory;
    private volatile TreeMap<Integer, Map<String, Integer>> byYear;

    public CiteScoreQuartiles(@Value("${cnfis.citescore.dir:data/scopus}") String directory) {
        this.directory = Path.of(directory);
    }

    /** The list years that were found. */
    public List<Integer> availableYears() {
        return new ArrayList<>(lists().keySet());
    }

    public Optional<Placement> placement(String scopusSourceId, int wantedListYear) {
        if (scopusSourceId == null || scopusSourceId.isBlank()) {
            return Optional.empty();
        }
        TreeMap<Integer, Map<String, Integer>> lists = lists();
        if (lists.isEmpty()) {
            return Optional.empty();
        }
        Integer year = lists.containsKey(wantedListYear) ? wantedListYear : nearest(lists, wantedListYear);
        Integer quartile = lists.get(year).get(scopusSourceId.trim());
        return quartile == null ? Optional.empty() : Optional.of(new Placement(quartile, year));
    }

    private static Integer nearest(TreeMap<Integer, Map<String, Integer>> lists, int wanted) {
        Integer below = lists.floorKey(wanted);
        Integer above = lists.ceilingKey(wanted);
        if (below == null) return above;
        if (above == null) return below;
        return wanted - below <= above - wanted ? below : above;
    }

    private TreeMap<Integer, Map<String, Integer>> lists() {
        TreeMap<Integer, Map<String, Integer>> loaded = byYear;
        if (loaded == null) {
            synchronized (this) {
                if (byYear == null) {
                    byYear = load(directory);
                }
                loaded = byYear;
            }
        }
        return loaded;
    }

    static TreeMap<Integer, Map<String, Integer>> load(Path directory) {
        TreeMap<Integer, Map<String, Integer>> out = new TreeMap<>();
        if (!Files.isDirectory(directory)) {
            log.warn("CiteScore directory {} not found: Anexa 5.3 has no Scopus quartiles", directory);
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                Matcher m = FILE.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                int year = Integer.parseInt(m.group(1));
                Map<String, Integer> best = readBestQuartiles(file);
                if (!best.isEmpty()) {
                    out.merge(year, best, (a, b) -> { b.forEach((k, v) -> a.merge(k, v, Math::min)); return a; });
                    log.info("CiteScore {} list: {} sources ({})", year, best.size(), file.getFileName());
                }
            }
        } catch (IOException ex) {
            log.warn("CiteScore lists not read from {}: {}", directory, ex.toString());
        }
        return out;
    }

    /**
     * source id → best (lowest) quartile over the subject-area rows of the file. Read as Latin-1: the export is
     * not UTF-8 (Windows-1252 titles), and only the id and quartile columns are used, which are plain digits.
     */
    static Map<String, Integer> readBestQuartiles(Path file) throws IOException {
        Map<String, Integer> best = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            String header = reader.readLine();
            if (header == null) {
                return best;
            }
            List<String> columns = splitCsv(header.startsWith("﻿") ? header.substring(1) : header);
            int idColumn = columns.indexOf("Scopus Source ID");
            int quartileColumn = columns.indexOf("Quartile");
            if (idColumn < 0 || quartileColumn < 0) {
                log.warn("{} has no 'Scopus Source ID' / 'Quartile' columns", file.getFileName());
                return best;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cells = splitCsv(line);
                if (cells.size() <= Math.max(idColumn, quartileColumn)) {
                    continue;
                }
                String id = cells.get(idColumn).trim();
                String q = cells.get(quartileColumn).trim();
                if (id.isEmpty() || !q.matches("[1-4]")) {
                    continue;
                }
                best.merge(id, Integer.parseInt(q), Math::min);
            }
        }
        return Collections.unmodifiableMap(best);
    }

    /** A plain CSV split honouring double quotes (the titles carry commas). */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString());
        return out;
    }
}
