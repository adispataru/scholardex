package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * H143 — classifies the publisher of a declared book from the lists each standard names ({@link PublisherRules}):
 * the commissions' own 2026 lists (through {@link PsihologiePublisherService}, as for corpus books), the WoS Master
 * Book List, the CNCS classifications of publishers in the humanities and arts (2013, 2020, 2026) and the UEFISCDI
 * list of publishers of international prestige in the arts and humanities. The lists ship as fixtures under
 * {@code report-data/}; a handful of common short names ({@code UVT}, {@code UNMB}, …) are aliases.
 */
@Service
public class PublisherCategoryService implements PublisherCategorySupport.Classifier {

    private static final Logger log = LoggerFactory.getLogger(PublisherCategoryService.class);

    static final String CNCS_FIXTURE = "report-data/cncs-publishers.csv";
    static final String INTERNATIONAL_FIXTURE = "report-data/uefiscdi-arts-humanities-international-publishers.csv";
    static final String ALIASES_FIXTURE = "report-data/publisher-aliases.csv";
    /**
     * The Music domain of the CNCS lists ("Muzică" in 2020 and 2026). The 2013 list rated the performing arts as a whole
     * ("Artele spectacolului"); it counts like any other domain, for a publisher the Music lists do not rate.
     */
    private static final Set<String> MUSIC_DOMAINS = Set.of("MUZICA");
    /** UEFISCDI excludes it from its own list of publishers of international prestige. */
    private static final List<String> EXCLUDED_INTERNATIONAL = PublisherNameMatcher.words("Lambert Academic Publishing");
    private static final Map<String, String> DOMAIN_LABELS = Map.ofEntries(
            Map.entry("ARHITECTURA", "Arhitectură și urbanism"), Map.entry("ARTE_VIZUALE", "Arte vizuale"),
            Map.entry("CINEMATOGRAFIE", "Cinematografie și media"), Map.entry("FILOLOGIE", "Filologie"),
            Map.entry("FILOSOFIE", "Filosofie"), Map.entry("ISTORIE", "Istorie și studii culturale"),
            Map.entry("MUZICA", "Muzică"), Map.entry("TEATRU", "Teatru și artele spectacolului"),
            Map.entry("TEOLOGIE", "Teologie"), Map.entry("ARTELE_SPECTACOLULUI", "Artele spectacolului"));

    /** One CNCS classification: the list's year, the domain, the category (A, B, C) and the publisher's words. */
    record CncsRow(int year, String domain, String category, String name, List<String> words) {
    }

    /** A publisher of the international list, as written there and as words. */
    record Listed(String name, List<String> words) {
    }

    private final PsihologiePublisherService commissionLists;
    private final WosMasterBookListService masterBookList;
    private final List<CncsRow> cncs;
    private final List<Listed> international;
    private final Map<List<String>, String> aliases;

    public PublisherCategoryService(PsihologiePublisherService commissionLists, WosMasterBookListService masterBookList) {
        this.commissionLists = commissionLists;
        this.masterBookList = masterBookList;
        this.cncs = loadCncs();
        this.international = loadInternational();
        this.aliases = loadAliases();
    }

    @PostConstruct
    void register() {
        PublisherCategorySupport.register(this);
        log.info("Publisher categories: {} CNCS classifications, {} international publishers, {} aliases",
                cncs.size(), international.size(), aliases.size());
    }

    @Override
    public Optional<PublisherCategorySupport.Classification> classify(PublisherRules rules, String publisher) {
        if (rules == null || publisher == null || publisher.isBlank()) {
            return Optional.empty();
        }
        String name = aliases.getOrDefault(PublisherNameMatcher.words(publisher), publisher);
        return switch (rules) {
            case SOCIOLOGIE_2026 -> commissionList(
                    commissionLists.tierFromList(Comisia25Rules.SOCIOLOGIE.publisherList(), name),
                    "Lista A2 a Comisiei 25 (Sociologie)", name);
            case PSIHOLOGIE_2026 -> commissionList(commissionLists.tierFor2026(Comisia28Rules.PSIHOLOGIE, name),
                    "Lista 2026 a Comisiei 28 (Psihologie)", name);
            case STIINTE_EDUCATIEI_2026 -> commissionList(commissionLists.tierFor2026(Comisia28Rules.STIINTE_EDUCATIEI, name),
                    "Lista 2026 a Comisiei 28 (Științe ale educației)", name);
            case MUZICA_2026 -> music(name);
        };
    }

    private Optional<PublisherCategorySupport.Classification> commissionList(String tier, String detail, String name) {
        if (tier != null) {
            return Optional.of(new PublisherCategorySupport.Classification(tier, "LIST", detail));
        }
        if (masterBookList.isRecognized(name)) {
            return Optional.of(new PublisherCategorySupport.Classification("A1", "WOS_MASTER_BOOK_LIST", "WoS Master Book List"));
        }
        return Optional.empty();
    }

    private Optional<PublisherCategorySupport.Classification> music(String name) {
        Optional<CncsRow> romanian = bestCncs(name, MUSIC_DOMAINS);
        if (romanian.isPresent() && !"C".equals(romanian.get().category())) {
            return romanian.map(PublisherCategoryService::cncsClassification);
        }
        if (onInternationalList(name)) {
            return Optional.of(new PublisherCategorySupport.Classification(PublisherRules.FOREIGN, "INTERNATIONAL_LIST",
                    "UEFISCDI — edituri de prestigiu internațional, arte și științe umaniste"));
        }
        if (masterBookList.isRecognized(name)) {
            return Optional.of(new PublisherCategorySupport.Classification(PublisherRules.FOREIGN, "WOS_MASTER_BOOK_LIST",
                    "WoS Master Book List"));
        }
        return romanian.map(PublisherCategoryService::cncsClassification); // a C: listed, but it does not count
    }

    private static PublisherCategorySupport.Classification cncsClassification(CncsRow row) {
        return new PublisherCategorySupport.Classification(row.category(), "CNCS",
                "CNCS " + row.year() + ", " + DOMAIN_LABELS.getOrDefault(row.domain(), row.domain()) + ": " + row.name());
    }

    /**
     * The publisher's best CNCS category: among its classifications in the preferred domains when it has any, else
     * among all of them; then the latest list. Every row the typed name matches is the same publisher under another
     * spelling ("Editura Universității de Vest" in 2013, "… din Timișoara" in 2026).
     */
    Optional<CncsRow> bestCncs(String name, Set<String> preferredDomains) {
        List<String> typed = PublisherNameMatcher.words(name);
        List<CncsRow> hits = cncs.stream().filter(row -> PublisherNameMatcher.match(row.words(), typed) > 0).toList();
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        List<CncsRow> preferred = hits.stream().filter(r -> preferredDomains.contains(r.domain())).toList();
        List<CncsRow> pool = preferred.isEmpty() ? hits : preferred;
        return pool.stream().min(Comparator.comparing(CncsRow::category).thenComparing(CncsRow::year, Comparator.reverseOrder()));
    }

    /**
     * The longest publisher name of the CNCS lists or of the international list whose words stand together in the
     * text; names of one word are too common in a free text to be trusted and are skipped.
     */
    @Override
    public Optional<String> findIn(String text) {
        List<String> words = PublisherNameMatcher.words(text);
        String best = null;
        int bestLength = 0;
        for (CncsRow row : cncs) {
            if (row.words().size() >= 2 && row.words().size() > bestLength && PublisherNameMatcher.match(row.words(), words) >= 2) {
                best = row.name();
                bestLength = row.words().size();
            }
        }
        for (Listed listed : international) {
            if (listed.words().size() >= 2 && listed.words().size() > bestLength
                    && PublisherNameMatcher.match(listed.words(), words) >= 2
                    && PublisherNameMatcher.match(EXCLUDED_INTERNATIONAL, listed.words()) == 0) {
                best = listed.name();
                bestLength = listed.words().size();
            }
        }
        return Optional.ofNullable(best);
    }

    boolean onInternationalList(String name) {
        List<String> typed = PublisherNameMatcher.words(name);
        if (typed.isEmpty() || PublisherNameMatcher.match(EXCLUDED_INTERNATIONAL, typed) > 0) {
            return false;
        }
        return international.stream().anyMatch(listed -> PublisherNameMatcher.match(listed.words(), typed) > 0);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private static List<CncsRow> loadCncs() {
        List<CncsRow> rows = new ArrayList<>();
        for (List<String> r : readCsv(CNCS_FIXTURE)) {
            if (r.size() < 4) continue;
            rows.add(new CncsRow(Integer.parseInt(r.get(0).trim()), r.get(1).trim(), r.get(2).trim().toUpperCase(),
                    r.get(3).trim(), PublisherNameMatcher.words(r.get(3))));
        }
        return List.copyOf(rows);
    }

    private static List<Listed> loadInternational() {
        List<Listed> names = new ArrayList<>();
        for (List<String> r : readCsv(INTERNATIONAL_FIXTURE)) {
            if (r.size() < 2) continue;
            List<String> words = PublisherNameMatcher.words(r.get(1));
            if (!words.isEmpty()) names.add(new Listed(r.get(1).trim(), words));
        }
        return List.copyOf(names);
    }

    private static Map<List<String>, String> loadAliases() {
        Map<List<String>, String> out = new HashMap<>();
        for (List<String> r : readCsv(ALIASES_FIXTURE)) {
            if (r.size() < 2) continue;
            out.put(PublisherNameMatcher.words(r.get(0)), r.get(1).trim());
        }
        return Map.copyOf(out);
    }

    /** The rows of a fixture without its header; fields may be double-quoted, with "" for a quote. */
    static List<List<String>> readCsv(String resource) {
        List<List<String>> rows = new ArrayList<>();
        try (InputStream in = new ClassPathResource(resource).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                if (!line.isBlank()) rows.add(splitCsv(line));
            }
        } catch (IOException e) {
            log.error("Publisher list {} could not be read", resource, e);
        }
        return rows;
    }

    static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                else if (c == '"') quoted = false;
                else current.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
