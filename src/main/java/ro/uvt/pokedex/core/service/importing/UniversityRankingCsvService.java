package ro.uvt.pokedex.core.service.importing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.UniversityRanking;
import ro.uvt.pokedex.core.repository.UniversityRankingRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * H83 S2 — CSV loader for generic multi-source university rankings ({@link UniversityRanking}).
 * Files: {@code <PREFIX>_WR_<year>.csv} with header {@code rank,rankBand,name,country} (rank already
 * band-lower-bounded by the one-off scraper). Load-once per source, mirroring the URAP loader's guard:
 * to reload after adding files, drop the source's docs and re-run the init step.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UniversityRankingCsvService {

    private final UniversityRankingRepository universityRankingRepository;

    public void loadSourceFromFolder(String source, String folderPath, String filePrefix) {
        if (universityRankingRepository.countBySource(source) > 0) {
            log.info("{} rankings already present — skipping load from {}", source, folderPath);
            return;
        }
        Pattern filePattern = Pattern.compile(Pattern.quote(filePrefix) + "_WR_(\\d{4})\\.csv");
        Map<String, UniversityRanking> byId = new HashMap<>();
        int[] rows = {0, 0};
        try (Stream<Path> paths = Files.walk(Paths.get(folderPath))) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                Matcher m = filePattern.matcher(path.getFileName().toString());
                if (!m.matches()) {
                    return;
                }
                int year = Integer.parseInt(m.group(1));
                loadFile(source, path, year, byId, rows);
            });
        } catch (IOException e) {
            log.error("Error scanning {} ranking folder {}", source, folderPath, e);
            throw new RuntimeException("Failed to read ranking directory " + folderPath, e);
        }
        universityRankingRepository.saveAll(new ArrayList<>(byId.values()));
        log.info("{} ranking import: {} universities from {} rows ({} skipped) in {}",
                source, byId.size(), rows[0], rows[1], folderPath);
    }

    private void loadFile(String source, Path path, int year, Map<String, UniversityRanking> byId, int[] rows) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + path, e);
        }
        for (int i = 1; i < lines.size(); i++) { // skip header
            List<String> cells = parseCsvLine(lines.get(i));
            if (cells.size() < 3 || cells.get(0).isBlank() || cells.get(2).isBlank()) {
                rows[1]++;
                continue;
            }
            int rank;
            try {
                rank = Integer.parseInt(cells.get(0).trim());
            } catch (NumberFormatException e) {
                rows[1]++;
                continue;
            }
            String name = cells.get(2).trim();
            UniversityRanking doc = byId.computeIfAbsent(UniversityRanking.composeId(source, name), id -> {
                UniversityRanking u = new UniversityRanking();
                u.setId(id);
                u.setName(name);
                u.setSource(source);
                u.setCountry(cells.size() > 3 ? cells.get(3).trim() : "");
                u.setRanks(new HashMap<>());
                return u;
            });
            UniversityRanking.RankEntry entry = new UniversityRanking.RankEntry();
            entry.setRank(rank);
            entry.setRankBand(cells.size() > 1 && !cells.get(1).isBlank() ? cells.get(1).trim() : String.valueOf(rank));
            doc.getRanks().put(year, entry);
            rows[0]++;
        }
    }

    /** Minimal RFC-4180 field split (quoted fields with embedded commas — university names have them). */
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
