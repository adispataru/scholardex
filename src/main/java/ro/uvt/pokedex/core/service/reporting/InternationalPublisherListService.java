package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.repository.reporting.SenseRankingRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * H143 — the international lists and rankings of publishers named by {@value #INDEX}: one row per list (key, the label a
 * researcher reads, its source, the levels that count). A source is either {@value #SENSE_SOURCE} — the SENSE ranking in
 * the database, imported from the admin initialization page and read by the Computer Science book scorer too — or a
 * fixture with a {@code name} column and, for a ranking, a {@code level} column. A further ranking is one more fixture
 * and one more row.
 *
 * <p>Names are compared as token sets ({@link WosMasterBookListService#canonicalTokens}): equal; the listed name inside
 * the typed one, when it holds an identifying word ("Routledge, London"); or the typed name inside the listed one when
 * the listed name adds generic words only ("Polity" for "Polity Press", not "Business Press" for "Harvard Business
 * School Press", nor "University of Arizona" for "Arizona State University"). A Romanian house is never looked up here
 * — written «Editura …», or named as on a Romanian list (CNCS, the commissions' 2026 lists, the Romanian houses of the
 * Master Book List) — so «Editura Economică» does not become the French Economica of Anexa 7c, nor Paideia an Italian
 * house. Lambert Academic Publishing never counts ({@link ExcludedPublishers}).</p>
 */
@Service
public class InternationalPublisherListService implements InternationalPublisherSupport.Lists {

    private static final Logger log = LoggerFactory.getLogger(InternationalPublisherListService.class);

    static final String INDEX = "report-data/international-publisher-lists.csv";
    static final String SENSE_SOURCE = "senseRankings";
    private static final List<String> ROMANIAN_LISTS = List.of(
            "report-data/cncs-publishers.csv", "report-data/sociologie-publishers-2026.csv",
            "report-data/psihologie-publishers-2026.csv", "report-data/stiinte-educatiei-publishers-2026.csv",
            "report-data/psihologie-publishers.csv");
    private static final String MASTER_BOOK_LIST = "report-data/wos-master-book-list-publishers.csv";

    /** One list of the index. */
    record Source(String key, String label, String location, Set<String> levels) {
        boolean counts(String level) {
            return levels.isEmpty() || (level != null && levels.contains(level.trim().toUpperCase(java.util.Locale.ROOT)));
        }
    }

    /** A publisher of a list: as the list writes it, and as tokens. */
    record Entry(String name, Set<String> tokens) {
    }

    private final SenseRankingRepository senseRankings;
    private final List<Source> sources;
    /** The entries of every source; SENSE's arrive from the database on first use. */
    private final Map<String, List<Entry>> entries = new ConcurrentHashMap<>();
    private final List<List<String>> romanianNames;
    private final ConcurrentMap<String, Optional<InternationalPublisherSupport.Recognition>> cache = new ConcurrentHashMap<>();
    /** An empty or unreachable SENSE collection is looked at again at most this often, not once per book. */
    private static final long RETRY_AFTER_MILLIS = 60_000;
    private volatile long lastDatabaseAttempt = Long.MIN_VALUE / 2;

    public InternationalPublisherListService(SenseRankingRepository senseRankings) {
        this.senseRankings = senseRankings;
        this.sources = loadIndex();
        for (Source source : sources) {
            if (!SENSE_SOURCE.equals(source.location())) {
                entries.put(source.key(), loadFixture(source));
            }
        }
        this.romanianNames = loadRomanianNames();
    }

    @PostConstruct
    void register() {
        InternationalPublisherSupport.register(this);
        log.info("International publisher lists: {} ({} Romanian names guard them)",
                sources.stream().map(s -> s.key() + (entries.containsKey(s.key()) ? "=" + entries.get(s.key()).size() : "=lazy"))
                        .collect(Collectors.joining(", ")), romanianNames.size());
    }

    @Override
    public Optional<InternationalPublisherSupport.Recognition> recognize(String publisher) {
        if (publisher == null || publisher.isBlank()) {
            return Optional.empty();
        }
        Optional<InternationalPublisherSupport.Recognition> cached = cache.get(publisher);
        if (cached != null) {
            return cached;
        }
        Optional<InternationalPublisherSupport.Recognition> found = recognizeUncached(publisher);
        if (allLoaded()) {
            cache.put(publisher, found);
        }
        return found;
    }

    private Optional<InternationalPublisherSupport.Recognition> recognizeUncached(String publisher) {
        Set<String> typed = WosMasterBookListService.canonicalTokens(publisher);
        if (typed.isEmpty() || ExcludedPublishers.isExcluded(publisher) || isRomanian(publisher)) {
            return Optional.empty();
        }
        InternationalPublisherSupport.Recognition contained = null;
        for (Source source : sources) {
            for (Entry entry : entriesOf(source)) {
                if (entry.tokens().equals(typed)) {
                    return Optional.of(new InternationalPublisherSupport.Recognition(source.key(), source.label(), entry.name()));
                }
                if (contained == null && matches(typed, entry.tokens())) {
                    contained = new InternationalPublisherSupport.Recognition(source.key(), source.label(), entry.name());
                }
            }
        }
        return Optional.ofNullable(contained);
    }

    @Override
    public boolean isRomanian(String publisher) {
        if (publisher == null || publisher.isBlank()) {
            return false;
        }
        if ((" " + FeaaAnexa1PublisherService.normalize(publisher) + " ").contains(" editura ")) {
            return true;
        }
        List<String> typed = PublisherNameMatcher.words(publisher);
        if (typed.isEmpty()) {
            return false;
        }
        if (romanianNames.stream().anyMatch(name -> PublisherNameMatcher.match(name, typed) == 3)) {
            return true;
        }
        // inside a longer name only with a word of its own (CNCS rates a Romanian "Editura University Press"), and
        // not when the whole name is one a foreign list holds ("Idea Group Publishing" is not Editura Idea)
        boolean inside = romanianNames.stream().anyMatch(name -> PublisherNameMatcher.match(name, typed) == 2
                && name.stream().anyMatch(w -> !WosMasterBookListService.GENERIC.contains(w)));
        return inside && !namedOnAList(publisher);
    }

    private boolean namedOnAList(String publisher) {
        Set<String> tokens = WosMasterBookListService.canonicalTokens(publisher);
        return sources.stream().anyMatch(source -> entriesOf(source).stream().anyMatch(e -> e.tokens().equals(tokens)));
    }

    @Override
    public Optional<InternationalPublisherSupport.Recognition> recognizeOn(String key, String publisher) {
        if (publisher == null || publisher.isBlank()) {
            return Optional.empty();
        }
        return recognize(publisher).filter(r -> r.key().equals(key)).or(() -> {
            Set<String> typed = WosMasterBookListService.canonicalTokens(publisher);
            if (typed.isEmpty() || ExcludedPublishers.isExcluded(publisher) || isRomanian(publisher)) {
                return Optional.empty();
            }
            return sources.stream().filter(s -> s.key().equals(key)).findFirst().flatMap(source -> {
                InternationalPublisherSupport.Recognition contained = null;
                for (Entry entry : entriesOf(source)) {
                    if (entry.tokens().equals(typed)) {
                        return Optional.of(new InternationalPublisherSupport.Recognition(source.key(), source.label(), entry.name()));
                    }
                    if (contained == null && matches(typed, entry.tokens())) {
                        contained = new InternationalPublisherSupport.Recognition(source.key(), source.label(), entry.name());
                    }
                }
                return Optional.ofNullable(contained);
            });
        });
    }

    @Override
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Source source : sources) {
            for (Entry entry : entriesOf(source)) {
                if (!ExcludedPublishers.isExcluded(entry.name())) {
                    names.add(entry.name());
                }
            }
        }
        return names;
    }

    /** Whether a typed name (as tokens) names a listed one; see the class comment. */
    static boolean matches(Set<String> typed, Set<String> listed) {
        if (typed.equals(listed)) {
            return true;
        }
        if (typed.containsAll(listed)) {
            return !identifying(listed).isEmpty();
        }
        if (listed.containsAll(typed)) {
            Set<String> words = identifying(typed);
            return !words.isEmpty() && words.containsAll(identifying(listed));
        }
        return false;
    }

    /** The words that tell one publisher from another: not generic ("Press", "University"), not a stray letter. */
    private static Set<String> identifying(Set<String> tokens) {
        return tokens.stream().filter(t -> t.length() >= 2 && !WosMasterBookListService.GENERIC.contains(t))
                .collect(Collectors.toSet());
    }

    private boolean allLoaded() {
        return sources.stream().allMatch(s -> entries.containsKey(s.key()));
    }

    private List<Entry> entriesOf(Source source) {
        List<Entry> loaded = entries.get(source.key());
        if (loaded != null) {
            return loaded;
        }
        long now = System.currentTimeMillis();
        if (now - lastDatabaseAttempt < RETRY_AFTER_MILLIS) {
            return List.of();
        }
        lastDatabaseAttempt = now;
        try {
            List<Entry> sense = new ArrayList<>();
            for (SenseBookRanking ranking : senseRankings.findAll()) {
                if (ranking.getRanking() != null && source.counts(ranking.getRanking().name())) {
                    addEntry(sense, ranking.getName());
                }
            }
            if (!sense.isEmpty()) { // an empty collection may still be imported: look again next time
                entries.put(source.key(), List.copyOf(sense));
                cache.clear();
                log.info("International publisher list {}: {} publishers", source.key(), sense.size());
            }
            return sense;
        } catch (DataAccessException e) {
            log.warn("International publisher list {} not loadable (database unreachable): {} — will retry on next use",
                    source.key(), e.getMessage());
            return List.of();
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private static List<Source> loadIndex() {
        List<Source> sources = new ArrayList<>();
        Table index = readTable(INDEX);
        for (List<String> row : index.rows()) {
            String key = index.get(row, "key"), label = index.get(row, "label"), location = index.get(row, "source");
            if (key.isBlank() || label.isBlank() || location.isBlank()) {
                continue;
            }
            Set<String> levels = Arrays.stream(index.get(row, "levels").split("\\|"))
                    .map(level -> level.trim().toUpperCase(java.util.Locale.ROOT))
                    .filter(level -> !level.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
            sources.add(new Source(key.trim(), label.trim(), location.trim(), levels));
        }
        return List.copyOf(sources);
    }

    private static List<Entry> loadFixture(Source source) {
        List<Entry> out = new ArrayList<>();
        Table table = readTable(source.location());
        for (List<String> row : table.rows()) {
            if (source.counts(table.get(row, "level"))) {
                addEntry(out, table.get(row, "name"));
            }
        }
        if (out.isEmpty()) {
            log.error("International publisher list {} ({}) holds no publisher", source.key(), source.location());
        }
        return List.copyOf(out);
    }

    private static void addEntry(List<Entry> out, String name) {
        Set<String> tokens = WosMasterBookListService.canonicalTokens(name);
        if (name != null && !tokens.isEmpty()) {
            out.add(new Entry(name.trim(), tokens));
        }
    }

    /** The names of the Romanian lists, and the Romanian houses («EDITURA …») of the WoS Master Book List. */
    private static List<List<String>> loadRomanianNames() {
        List<List<String>> names = new ArrayList<>();
        for (String resource : ROMANIAN_LISTS) {
            Table table = readTable(resource);
            for (List<String> row : table.rows()) {
                List<String> words = PublisherNameMatcher.words(table.get(row, "name"));
                if (!words.isEmpty()) names.add(words);
            }
        }
        Table masterBookList = readTable(MASTER_BOOK_LIST);
        for (List<String> row : masterBookList.rows()) {
            String name = masterBookList.get(row, "name");
            List<String> words = PublisherNameMatcher.words(name);
            if (name.trim().toUpperCase(java.util.Locale.ROOT).startsWith("EDITURA ") && !words.isEmpty()) names.add(words);
        }
        return List.copyOf(names);
    }

    /** A fixture: its header, and its rows read field by field ("…" quotes allowed). */
    record Table(Map<String, Integer> columns, List<List<String>> rows) {
        String get(List<String> row, String column) {
            Integer i = columns.get(column);
            return i == null || i >= row.size() ? "" : row.get(i).trim();
        }
    }

    static Table readTable(String resource) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        List<List<String>> rows = new ArrayList<>();
        try (InputStream in = new ClassPathResource(resource).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line != null) {
                List<String> header = PublisherCategoryService.splitCsv(line.replace("﻿", ""));
                for (int i = 0; i < header.size(); i++) columns.put(header.get(i).trim().toLowerCase(java.util.Locale.ROOT), i);
            }
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) rows.add(PublisherCategoryService.splitCsv(line));
            }
        } catch (IOException e) {
            log.error("Publisher list {} could not be read", resource, e);
        }
        return new Table(columns, rows);
    }
}
