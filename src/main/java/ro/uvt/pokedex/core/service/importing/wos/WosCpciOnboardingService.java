package ro.uvt.pokedex.core.service.importing.wos;

import com.opencsv.CSVReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.QueryNormalizationSupport;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;
import ro.uvt.pokedex.core.service.reporting.ConferenceTitleNormalizationSupport;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * H76: onboard WoS Conference Proceedings Citation Index (CPCI) coverage by matching a WoS Records export
 * ({@link WosCpciRecord}) against our forum/publication registry and tagging the matched forums WoS-indexed.
 *
 * <p>These records are UVT papers — the same corpus we hold — so we match WoS <i>records</i> to our
 * <i>publications/forums</i> by exact keys (DOI → ISSN/ISBN → conference title), not WoS venue <i>names</i> to our
 * forum names. S1 (this class) is the dry-run: it reports the per-key match rate + the distinct forums that would be
 * tagged, with no writes. S2 will apply the {@code wosForumIds} tag.
 */
@Service
public class WosCpciOnboardingService {

    private static final Logger logger = LoggerFactory.getLogger(WosCpciOnboardingService.class);
    private static final int DOI_BATCH = 1000;
    private static final int SAMPLE_CAP = 50;

    private final ScholardexForumFactRepository forumFactRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;

    @Value("${wos.cpci.file:data/wos/cpci/uvt-proceedings.csv}")
    private String cpciFile;

    public WosCpciOnboardingService(ScholardexForumFactRepository forumFactRepository,
                                    ScholardexPublicationFactRepository publicationFactRepository) {
        this.forumFactRepository = forumFactRepository;
        this.publicationFactRepository = publicationFactRepository;
    }

    /** S1: load the configured CPCI export and report the dry-run match against the live forum/publication registry. */
    public WosCpciMatchReport dryRun() {
        return dryRun(cpciFile);
    }

    public WosCpciMatchReport dryRun(String path) {
        List<WosCpciRecord> records = loadRecords(path);
        List<ScholardexForumFact> forums = forumFactRepository.findAll();
        Map<String, String> doiToForumId = resolveDoiForumIds(records);
        WosCpciMatchReport report = match(records, forums, doiToForumId);
        logger.info("H76 CPCI dry-run [{}]: {} records → matched DOI={} ISSN/ISBN={} title={} (unmatched {}); "
                        + "{} distinct forums ({} net-new WoS, {} already WoS)",
                path, report.totalRecords(), report.matchedByDoi(), report.matchedByIssnIsbn(),
                report.matchedByTitle(), report.unmatched(), report.distinctForumsMatched(),
                report.forumsNetNew(), report.forumsAlreadyWos());
        return report;
    }

    private Map<String, String> resolveDoiForumIds(List<WosCpciRecord> records) {
        Set<String> dois = new HashSet<>();
        for (WosCpciRecord r : records) {
            String norm = ScholardexPublicationCanonicalizationService.normalizeDoi(r.doi());
            if (norm != null) {
                dois.add(norm);
            }
        }
        Map<String, String> doiToForumId = new HashMap<>();
        List<String> batch = new ArrayList<>(dois);
        for (int i = 0; i < batch.size(); i += DOI_BATCH) {
            List<String> slice = batch.subList(i, Math.min(i + DOI_BATCH, batch.size()));
            for (ScholardexPublicationFact pub : publicationFactRepository.findAllByDoiNormalizedIn(slice)) {
                if (pub.getDoiNormalized() != null && pub.getForumId() != null && !pub.getForumId().isBlank()) {
                    doiToForumId.putIfAbsent(pub.getDoiNormalized(), pub.getForumId());
                }
            }
        }
        return doiToForumId;
    }

    /**
     * Pure matcher (no I/O) — testable. For each record, resolve a forum by precedence DOI &gt; ISSN/eISSN/ISBN &gt;
     * conference/source title, and aggregate the distinct forums that would be tagged WoS-indexed.
     */
    static WosCpciMatchReport match(List<WosCpciRecord> records,
                                    List<ScholardexForumFact> forums,
                                    Map<String, String> doiNormToForumId) {
        Map<String, String> issnIndex = new HashMap<>();
        Map<String, String> isbnIndex = new HashMap<>();
        Map<String, String> nameIndex = new HashMap<>();
        Set<String> alreadyWosForumIds = new HashSet<>();
        for (ScholardexForumFact f : forums) {
            if (f.getId() == null) {
                continue;
            }
            indexIssn(issnIndex, f.getIssn(), f.getId());
            indexIssn(issnIndex, f.getEIssn(), f.getId());
            if (f.getAliasIssns() != null) {
                f.getAliasIssns().forEach(a -> indexIssn(issnIndex, a, f.getId()));
            }
            String isbnKey = normalizeIsbn(f.getIsbn());
            if (isbnKey != null) {
                isbnIndex.putIfAbsent(isbnKey, f.getId());
            }
            indexName(nameIndex, f.getNameNormalized(), f.getId());
            indexName(nameIndex, ConferenceTitleNormalizationSupport.normalizeVenueName(f.getName()), f.getId());
            if (f.getWosForumIds() != null && !f.getWosForumIds().isEmpty()) {
                alreadyWosForumIds.add(f.getId());
            }
        }

        int matchedByDoi = 0;
        int matchedByIssnIsbn = 0;
        int matchedByTitle = 0;
        Set<String> matchedForumIds = new HashSet<>();
        Map<String, Integer> unmatchedVenues = new LinkedHashMap<>();

        for (WosCpciRecord r : records) {
            String forumId = null;
            String doiNorm = ScholardexPublicationCanonicalizationService.normalizeDoi(r.doi());
            if (doiNorm != null) {
                forumId = doiNormToForumId.get(doiNorm);
            }
            if (forumId != null) {
                matchedByDoi++;
            } else {
                forumId = byIssnOrIsbn(issnIndex, isbnIndex, r);
                if (forumId != null) {
                    matchedByIssnIsbn++;
                } else {
                    forumId = byTitle(nameIndex, r);
                    if (forumId != null) {
                        matchedByTitle++;
                    }
                }
            }
            if (forumId != null) {
                matchedForumIds.add(forumId);
            } else {
                String label = r.venueLabel();
                if (label != null && !label.isBlank()) {
                    unmatchedVenues.merge(label.trim(), 1, Integer::sum);
                }
            }
        }

        int alreadyWos = (int) matchedForumIds.stream().filter(alreadyWosForumIds::contains).count();
        List<String> netNew = matchedForumIds.stream()
                .filter(id -> !alreadyWosForumIds.contains(id))
                .sorted()
                .limit(SAMPLE_CAP)
                .toList();
        List<WosCpciMatchReport.UnmatchedVenue> topUnmatched = unmatchedVenues.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(SAMPLE_CAP)
                .map(e -> new WosCpciMatchReport.UnmatchedVenue(e.getKey(), e.getValue()))
                .toList();

        int unmatched = matchedByDoi + matchedByIssnIsbn + matchedByTitle;
        unmatched = records.size() - unmatched;
        return new WosCpciMatchReport(
                records.size(),
                matchedByDoi,
                matchedByIssnIsbn,
                matchedByTitle,
                unmatched,
                matchedForumIds.size(),
                alreadyWos,
                matchedForumIds.size() - alreadyWos,
                netNew,
                topUnmatched
        );
    }

    private static String byIssnOrIsbn(Map<String, String> issnIndex, Map<String, String> isbnIndex, WosCpciRecord r) {
        String issn = QueryNormalizationSupport.normalizeIssn(r.issn());
        if (issn != null && issnIndex.containsKey(issn)) {
            return issnIndex.get(issn);
        }
        String eIssn = QueryNormalizationSupport.normalizeIssn(r.eIssn());
        if (eIssn != null && issnIndex.containsKey(eIssn)) {
            return issnIndex.get(eIssn);
        }
        String isbn = normalizeIsbn(r.isbn());
        if (isbn != null && isbnIndex.containsKey(isbn)) {
            return isbnIndex.get(isbn);
        }
        return null;
    }

    private static String byTitle(Map<String, String> nameIndex, WosCpciRecord r) {
        String conf = ConferenceTitleNormalizationSupport.normalizeVenueName(r.conferenceTitle());
        if (!conf.isEmpty() && nameIndex.containsKey(conf)) {
            return nameIndex.get(conf);
        }
        String source = ConferenceTitleNormalizationSupport.normalizeVenueName(r.sourceTitle());
        if (!source.isEmpty() && nameIndex.containsKey(source)) {
            return nameIndex.get(source);
        }
        return null;
    }

    private static void indexIssn(Map<String, String> index, String raw, String forumId) {
        String key = QueryNormalizationSupport.normalizeIssn(raw);
        if (key != null) {
            index.putIfAbsent(key, forumId);
        }
    }

    private static void indexName(Map<String, String> index, String normalizedName, String forumId) {
        if (normalizedName != null && !normalizedName.isBlank()) {
            index.putIfAbsent(normalizedName, forumId);
        }
    }

    /** Strip hyphens/spaces, upper-case (ISBN-10 can end in 'X'). Null/blank → null. */
    static String normalizeIsbn(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    List<WosCpciRecord> loadRecords(String path) {
        File file = new File(path);
        if (!file.exists()) {
            logger.warn("H76 CPCI file not found, skipping: {}", path);
            return List.of();
        }
        List<WosCpciRecord> records = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String[] header = reader.readNext();
            if (header == null) {
                return List.of();
            }
            Map<String, Integer> col = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                col.put(header[i].trim(), i);
            }
            String[] row;
            while ((row = reader.readNext()) != null) {
                records.add(new WosCpciRecord(
                        cell(row, col, "ut"),
                        cell(row, col, "doi"),
                        cell(row, col, "sourceTitle"),
                        cell(row, col, "conferenceTitle"),
                        cell(row, col, "bookSeriesTitle"),
                        cell(row, col, "issn"),
                        cell(row, col, "eIssn"),
                        cell(row, col, "isbn"),
                        cell(row, col, "year")
                ));
            }
        } catch (Exception e) {
            logger.error("H76 failed to load CPCI file {}: {}", path, e.getMessage());
            return List.of();
        }
        return records;
    }

    private static String cell(String[] row, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null || idx >= row.length) {
            return null;
        }
        String v = row[idx];
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
