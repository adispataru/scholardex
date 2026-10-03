package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WosMasterBookListPublisher;
import ro.uvt.pokedex.core.repository.reporting.WosMasterBookListPublisherRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * H98: membership in the Web of Science Master Book List — "editurile recunoscute Web of Science" of
 * the physics standard. Same fixture-reconcile mechanics as {@link FeaaAnexa1PublisherService}:
 * missing rows are inserted on every load (never deleted), so already-seeded databases pick up list
 * edits without a manual import; matching goes through the shared normalizer so the list's
 * shouty abbreviated style ("ACADEMIC PRESS", "ADDISON-WESLEY PUBL CO") still meets Scopus/OpenAlex
 * publisher strings. Best-effort loading with lazy retry (a hard @PostConstruct dependency on Mongo
 * took Quality Gates down once).
 *
 * <p>The list is a frozen archive snapshot of a discontinued Clarivate page — see
 * {@link WosMasterBookListPublisher}. Treat a miss as "not on the 2026 snapshot", not as "not
 * recognised by Clarivate today".
 */
@Service
@RequiredArgsConstructor
public class WosMasterBookListService {

    private static final Logger log = LoggerFactory.getLogger(WosMasterBookListService.class);
    private static final String FIXTURE = "report-data/wos-master-book-list-publishers.csv";

    /**
     * The two catalogues spell publishers differently — Web of Science abbreviates and shouts
     * ("OXFORD UNIV PRESS", "JOHN WILEY &amp; SONS LTD", "WORLD SCIENTIFIC PUBL CO PTE LTD") while
     * Scopus/OpenAlex write them out ("Oxford University Press", "wiley", "World Scientific").
     * Exact matching therefore misses almost every major house, so names are compared as canonical
     * TOKEN SETS: abbreviations expanded, legal-form noise dropped.
     */
    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("univ", "university"), Map.entry("publ", "publishing"),
            Map.entry("publs", "publishing"), Map.entry("pub", "publishing"),
            Map.entry("intl", "international"), Map.entry("int", "international"),
            Map.entry("natl", "national"), Map.entry("nat", "national"),
            Map.entry("assoc", "association"), Map.entry("soc", "society"),
            Map.entry("acad", "academic"), Map.entry("sci", "science"),
            Map.entry("edit", "editions"), Map.entry("ed", "editions"));

    /** Legal forms and connectives that carry no identity. */
    private static final Set<String> NOISE = Set.of(
            "ltd", "inc", "co", "corp", "gmbh", "ag", "plc", "bv", "nv", "sa", "srl", "spa", "kg",
            "llc", "pte", "pvt", "sdn", "bhd", "as", "ab", "oy", "aps", "and", "of", "the", "for",
            "a", "s", "group", "limited", "incorporated");

    /**
     * Tokens too common to identify a publisher on their own: a candidate whose only shared tokens
     * are these must match the full set, never a subset — otherwise a publisher literally called
     * "Press" would match half the list.
     */
    static final Set<String> GENERIC = Set.of(
            "press", "publishing", "publishers", "publication", "publications", "books", "book",
            "editions", "verlag", "university", "international", "national", "house", "media",
            "imprint", "science", "sciences", "scientific", "academic", "academy", "society",
            "association", "college", "institute", "school", "studies");

    /** H145 — Romanian generic words of an imprint ("Editura Universității", "Casa Cărții de Știință"): no identity. */
    static final Set<String> GENERIC_RO = Set.of("editura", "editurii", "edituri", "presa", "presele", "casa", "cartii",
            "carte", "universitatii", "universitara", "universitar", "academiei", "academica", "stiinta", "stiintifica",
            "de", "din", "si", "la", "al", "ale", "lui",
            // legal forms as Romanian imprints write them: R.A., S.A., S.R.L., S.C.
            "r", "l", "ra", "sc", "srl");

    /** H145 — places an imprint is written with ("Polirom, Iași", "L'Harmattan, Paris"): an address, not a house. */
    static final Set<String> PLACES = Set.of("bucuresti", "iasi", "cluj", "napoca", "timisoara", "craiova", "constanta",
            "brasov", "sibiu", "oradea", "arad", "galati", "bacau", "ploiesti", "pitesti", "suceava", "targu", "mures",
            "alba", "iulia", "baia", "mare", "chisinau", "romania", "paris", "london", "berlin", "new", "york", "wien",
            "vienna", "munchen", "munich", "leiden", "boston", "amsterdam", "frankfurt", "main", "basel", "geneve",
            "geneva", "roma", "milano", "madrid", "barcelona", "lisboa", "warszawa", "budapest", "praha", "usa", "uk",
            "kassel", "richmond", "stuttgart", "leipzig", "hamburg", "koln", "cologne", "zurich", "bern", "mainz",
            "heidelberg", "dordrecht", "abingdon", "milton", "park", "thousand", "oaks", "hoboken", "westport",
            "newcastle", "tyne", "upon", "lanham", "bristol", "manchester", "edinburgh", "dublin", "torino", "firenze",
            "bologna", "napoli", "venezia", "padova", "lyon", "bruxelles", "brussels", "leuven", "antwerpen", "bratislava",
            "sofia", "beograd", "zagreb", "ljubljana", "kiev", "kyiv", "moscow", "moskva", "istanbul", "ankara", "athens",
            "athina", "tel", "aviv", "jerusalem", "tokyo", "beijing", "shanghai", "singapore", "sydney", "melbourne",
            "toronto", "montreal", "ottawa", "vancouver", "mexico", "buenos", "aires", "sao", "paulo", "rio", "janeiro",
            "santiago", "bogota", "lima", "caracas", "havana", "cairo", "johannesburg", "cape", "town", "nairobi", "delhi",
            "mumbai", "kolkata", "chennai", "bangalore", "seoul", "hong", "kong", "taipei", "bangkok", "manila", "jakarta");

    /**
     * H145 — whether the words a typed name adds to a listed one are only an imprint's dress (generic words, legal forms,
     * a place, a year): "Editura Polirom, Iași, 2019" is Polirom, "Editura Mirton, distribuită de Polirom" is not.
     */
    static boolean onlyAddress(Set<String> extras) {
        for (String t : extras) {
            if (!(GENERIC.contains(t) || GENERIC_RO.contains(t) || PLACES.contains(t) || NOISE.contains(t) || t.matches("\\d+"))) {
                return false;
            }
        }
        return true;
    }

    private final WosMasterBookListPublisherRepository repository;
    private final AtomicReference<List<Set<String>>> tokenSets = new AtomicReference<>(List.of());
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    @PostConstruct
    void init() {
        tryLoad();
    }

    /**
     * True when the publisher is on the WoS Master Book List snapshot. Matches when the canonical
     * token sets are equal, or when one is a subset of the other AND the smaller set contributes at
     * least one identifying (non-{@link #GENERIC}) token — so "wiley" reaches
     * "JOHN WILEY &amp; SONS LTD" and "Springer International Publishing" reaches "SPRINGER", while a
     * bare "Press" reaches nothing.
     */
    public boolean isRecognized(String publisherName) {
        Set<String> candidate = canonicalTokens(publisherName);
        if (candidate.isEmpty()) {
            return false;
        }
        if (!loaded.get()) {
            tryLoad(); // self-heals once the database is reachable
        }
        for (Set<String> listed : tokenSets.get()) {
            if (matches(candidate, listed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Set<String> candidate, Set<String> listed) {
        if (candidate.equals(listed)) {
            return true;
        }
        Set<String> smaller = candidate.size() <= listed.size() ? candidate : listed;
        Set<String> larger = smaller == candidate ? listed : candidate;
        if (!larger.containsAll(smaller)) {
            return false;
        }
        if (larger == candidate) {
            // H145: the typed name holds the listed one — only an imprint's dress may come with it
            Set<String> extras = new java.util.HashSet<>(candidate);
            extras.removeAll(listed);
            if (!onlyAddress(extras)) {
                return false;
            }
        }
        return smaller.stream().anyMatch(t -> !GENERIC.contains(t));
    }

    /** Normalized → abbreviations expanded → noise dropped → distinct tokens. */
    static Set<String> canonicalTokens(String name) {
        String normalized = FeaaAnexa1PublisherService.normalize(name);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<String> tokens = new java.util.LinkedHashSet<>();
        for (String raw : normalized.split(" ")) {
            String token = ABBREVIATIONS.getOrDefault(raw, raw);
            if (!token.isBlank() && !NOISE.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private synchronized void tryLoad() {
        if (loaded.get()) {
            return;
        }
        try {
            reconcileFixture();
            reloadCache();
            loaded.set(true);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("WoS Master Book List not loadable (database unreachable): {} — will retry on first use",
                    e.getMessage());
        }
    }

    private void reloadCache() {
        List<Set<String>> sets = new ArrayList<>();
        for (WosMasterBookListPublisher p : repository.findAll()) {
            Set<String> tokens = canonicalTokens(p.getName());
            if (!tokens.isEmpty()) {
                sets.add(tokens);
            }
        }
        tokenSets.set(List.copyOf(sets));
        log.info("WoS Master Book List loaded: {} publishers", sets.size());
    }

    private void reconcileFixture() {
        try (InputStream in = new ClassPathResource(FIXTURE).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Set<String> existing = new HashSet<>();
            for (WosMasterBookListPublisher p : repository.findAll()) {
                existing.add(FeaaAnexa1PublisherService.normalize(p.getName()));
            }
            List<WosMasterBookListPublisher> batch = new ArrayList<>();
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                int comma = line.indexOf(',');
                if (comma < 0) continue;
                WosMasterBookListPublisher p = new WosMasterBookListPublisher();
                String nr = line.substring(0, comma).trim();
                try { p.setNr(Integer.parseInt(nr)); } catch (NumberFormatException ignored) { }
                p.setName(stripQuotes(line.substring(comma + 1).trim()));
                if (!existing.contains(FeaaAnexa1PublisherService.normalize(p.getName()))) {
                    batch.add(p);
                }
            }
            if (!batch.isEmpty()) {
                repository.saveAll(batch);
                log.info("Reconciled WoS Master Book List from {}: {} new rows", FIXTURE, batch.size());
            }
        } catch (IOException e) {
            log.error("Failed to reconcile the WoS Master Book List from {}", FIXTURE, e);
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }
}
