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
    /** Containment matching only fires for titles this long, so a generic phrase can't coincidentally match. */
    private static final int MIN_CONTAINMENT_LEN = 30;

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
        return run(cpciFile, false);
    }

    public WosCpciMatchReport dryRun(String path) {
        return run(path, false);
    }

    /** S2: like {@link #dryRun()} but, when {@code commit} is true, tags the net-new conference forums WoS-indexed. */
    public WosCpciMatchReport apply(boolean commit) {
        return run(cpciFile, commit);
    }

    private WosCpciMatchReport run(String path, boolean commit) {
        List<WosCpciRecord> records = loadRecords(path);
        List<ScholardexForumFact> forums = forumFactRepository.findAll();
        Map<String, String> doiToForumId = resolveDoiForumIds(records);
        MatchResult result = matchAll(records, forums, doiToForumId);

        int tagged = 0;
        if (commit && !result.netNewForumIds().isEmpty()) {
            Map<String, ScholardexForumFact> byId = new HashMap<>();
            for (ScholardexForumFact f : forums) {
                if (f.getId() != null) {
                    byId.put(f.getId(), f);
                }
            }
            List<ScholardexForumFact> toTag = new ArrayList<>();
            for (String forumId : result.netNewForumIds()) {
                ScholardexForumFact f = byId.get(forumId);
                if (f != null && !f.isWosCpciIndexed()) {
                    f.setWosCpciIndexed(true);
                    toTag.add(f);
                }
            }
            if (!toTag.isEmpty()) {
                forumFactRepository.saveAll(toTag);
            }
            tagged = toTag.size();
        }

        WosCpciMatchReport report = toReport(result, commit, tagged);
        logger.info("H76 CPCI {} [{}]: {} records → DOI={} ISSN/ISBN={} title={} contains={} (unmatched {}); "
                        + "{} distinct forums, {} net-new WoS, {} already WoS{}",
                commit ? "APPLY" : "dry-run", path, report.totalRecords(), report.matchedByDoi(),
                report.matchedByIssnIsbn(), report.matchedByTitle(), report.matchedByTitleContains(),
                report.unmatched(), report.distinctForumsMatched(), report.forumsNetNew(), report.forumsAlreadyWos(),
                commit ? " → TAGGED " + tagged : "");
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

    /** Full match outcome (no I/O) used by both the dry-run report and the apply step. */
    record MatchResult(
            int totalRecords,
            int matchedByDoi,
            int matchedByIssnIsbn,
            int matchedByTitle,
            int matchedByTitleContains,
            Set<String> matchedForumIds,
            Set<String> netNewForumIds,
            Map<String, Integer> unmatchedVenues
    ) {
    }

    /**
     * Pure matcher (no I/O) — testable. For each record, resolve a forum by precedence DOI &gt; ISSN/eISSN/ISBN &gt;
     * exact conference/source title &gt; title containment, and split the distinct matched forums into already-WoS
     * (journal Master List or a prior CPCI tag) vs net-new conference forums to tag.
     */
    static MatchResult matchAll(List<WosCpciRecord> records,
                                List<ScholardexForumFact> forums,
                                Map<String, String> doiNormToForumId) {
        Map<String, String> issnIndex = new HashMap<>();
        Map<String, String> isbnIndex = new HashMap<>();
        Map<String, String> nameIndex = new HashMap<>();
        List<Map.Entry<String, String>> containmentNames = new ArrayList<>();
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
            String normName = ConferenceTitleNormalizationSupport.normalizeVenueName(f.getName());
            indexName(nameIndex, f.getNameNormalized(), f.getId());
            indexName(nameIndex, normName, f.getId());
            if (normName.length() >= MIN_CONTAINMENT_LEN) {
                containmentNames.add(Map.entry(normName, f.getId()));
            }
            // Idempotent: a forum already WoS via journal ids OR a prior CPCI tag is not "net-new".
            if ((f.getWosForumIds() != null && !f.getWosForumIds().isEmpty()) || f.isWosCpciIndexed()) {
                alreadyWosForumIds.add(f.getId());
            }
        }

        int matchedByDoi = 0;
        int matchedByIssnIsbn = 0;
        int matchedByTitle = 0;
        int matchedByTitleContains = 0;
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
            } else if ((forumId = byIssnOrIsbn(issnIndex, isbnIndex, r)) != null) {
                matchedByIssnIsbn++;
            } else if ((forumId = byTitle(nameIndex, r)) != null) {
                matchedByTitle++;
            } else if ((forumId = byTitleContains(containmentNames, r)) != null) {
                matchedByTitleContains++;
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

        Set<String> netNew = new HashSet<>();
        for (String id : matchedForumIds) {
            if (!alreadyWosForumIds.contains(id)) {
                netNew.add(id);
            }
        }
        return new MatchResult(records.size(), matchedByDoi, matchedByIssnIsbn, matchedByTitle, matchedByTitleContains,
                matchedForumIds, netNew, unmatchedVenues);
    }

    private static WosCpciMatchReport toReport(MatchResult r, boolean committed, int tagged) {
        int alreadyWos = r.matchedForumIds().size() - r.netNewForumIds().size();
        int unmatched = r.totalRecords()
                - r.matchedByDoi() - r.matchedByIssnIsbn() - r.matchedByTitle() - r.matchedByTitleContains();
        List<String> netNewSample = r.netNewForumIds().stream().sorted().limit(SAMPLE_CAP).toList();
        List<WosCpciMatchReport.UnmatchedVenue> topUnmatched = r.unmatchedVenues().entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(SAMPLE_CAP)
                .map(e -> new WosCpciMatchReport.UnmatchedVenue(e.getKey(), e.getValue()))
                .toList();
        return new WosCpciMatchReport(
                r.totalRecords(), r.matchedByDoi(), r.matchedByIssnIsbn(), r.matchedByTitle(),
                r.matchedByTitleContains(), unmatched, r.matchedForumIds().size(), alreadyWos,
                r.netNewForumIds().size(), tagged, committed, netNewSample, topUnmatched);
    }

    /**
     * Title-containment fallback: a record matches a forum when the record's normalized conference/source title is a
     * (≥{@value #MIN_CONTAINMENT_LEN}-char) substring of the forum's normalized name. Recovers per-edition Scopus
     * proceedings forums (e.g. {@code "Proceedings - 9th … SYNASC 2007"}) whose embedded edition number + acronym +
     * year defeat exact equality. Ties → the shortest forum name (most specific).
     */
    private static String byTitleContains(List<Map.Entry<String, String>> containmentNames, WosCpciRecord r) {
        for (String t : List.of(
                ConferenceTitleNormalizationSupport.normalizeVenueName(r.conferenceTitle()),
                ConferenceTitleNormalizationSupport.normalizeVenueName(r.sourceTitle()))) {
            if (t.length() < MIN_CONTAINMENT_LEN) {
                continue;
            }
            String best = null;
            int bestLen = Integer.MAX_VALUE;
            for (Map.Entry<String, String> e : containmentNames) {
                if (e.getKey().contains(t) && e.getKey().length() < bestLen) {
                    best = e.getValue();
                    bestLen = e.getKey().length();
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
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
