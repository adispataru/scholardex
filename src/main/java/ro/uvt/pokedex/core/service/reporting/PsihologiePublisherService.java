package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.PsihologiePublisher;
import ro.uvt.pokedex.core.repository.reporting.PsihologiePublisherRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Loads and matches the FSP (Psihologie, Anexa 28) classified-publisher list. Mirrors
 * {@link FeaaAnexa1PublisherService} (self-seeds Mongo from a bundled CSV, matches on normalized
 * names), but returns the publisher's <b>tier</b> (A1/A2/B) rather than a boolean, so the
 * {@code PSYCH_BOOK} scorer can map it to the fișă multiplier {@code m}.
 *
 * <p>Matching is normalized whole-string equality first, then a normalized-containment fallback
 * (a listed name is a token-substring of the actual publisher, so "Editura Polirom, Iaşi" still
 * matches the "Editura Polirom" entry). The longest matching entry wins to avoid a short national
 * name shadowing a more specific one.
 */
@Service
@RequiredArgsConstructor
public class PsihologiePublisherService {

    private static final Logger log = LoggerFactory.getLogger(PsihologiePublisherService.class);
    private static final String FIXTURE = "report-data/psihologie-publishers.csv";
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private final PsihologiePublisherRepository repository;
    /** normalized publisher name → tier (A1/A2/B). */
    private final AtomicReference<Map<String, String>> tierByName = new AtomicReference<>(Map.of());
    private final AtomicBoolean loaded = new AtomicBoolean(false);
    /**
     * Per 2026 list (keyed by its classpath location): normalized publisher name → tier (A2/B). Read
     * straight from the classpath ({@link Comisia28Rules#publisherList()},
     * {@link Comisia25Rules#publisherList()}), never from Mongo: the collection holds the 2016
     * list that the frozen FV Psihologie 2016 report keeps scoring with, and it seeds only when empty, so it
     * could not pick the 2026 rows up on an already-seeded database.
     */
    private final Map<String, Map<String, String>> tiers2026 = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        tryLoad();
    }

    /**
     * The FSP tier (A1/A2/B) for a publisher, or {@code null} if it is not on any list — an unlisted
     * publisher is not punctable per the fișă ("nu se punctează").
     */
    public String tierFor(String publisherName) {
        String normalized = normalize(publisherName);
        if (normalized.isEmpty()) {
            return null;
        }
        if (!loaded.get()) {
            tryLoad(); // self-heals once the database is reachable
        }
        return match(tierByName.get(), normalized);
    }

    /**
     * The 2026 (OM 3019/2025, Comisia 28) tier — A2 or B — for a publisher in the lists of the given
     * domain, or {@code null} when it is on neither list. Same matching as {@link #tierFor}; A1 is not a list (the standard defines it by WorldCat
     * holdings), so it never comes from here.
     */
    public String tierFor2026(Comisia28Rules rules, String publisherName) {
        return tierFromList(rules.publisherList(), publisherName);
    }

    /**
     * The tier a publisher has in one of the 2026 classpath lists ({@code nr,name,tier}), or {@code null}
     * when it is not on it. Serves every commission that publishes such a list: Comisia 28 through
     * {@link #tierFor2026}, Comisia 25 ({@link Comisia25Rules#publisherList()}) directly.
     */
    public String tierFromList(String classpathList, String publisherName) {
        String normalized = normalize(publisherName);
        if (normalized.isEmpty() || classpathList == null) {
            return null;
        }
        return match(tiers2026.computeIfAbsent(classpathList, PsihologiePublisherService::loadFixture2026), normalized);
    }

    private static String match(Map<String, String> tiers, String normalized) {
        String exact = tiers.get(normalized);
        if (exact != null) {
            return exact;
        }
        // Containment fallback: longest listed name that is a whole-token substring of the actual name — H145: when what
        // the typed name adds is only an imprint's dress ("Editura Polirom, Iași"), never another house's name
        // ("Editura Mirton, distribuită de Polirom")
        String bestMatch = null;
        for (Map.Entry<String, String> e : tiers.entrySet()) {
            String listed = e.getKey();
            if (containsWholeTokens(normalized, listed)
                    && WosMasterBookListService.onlyAddress(extraTokens(normalized, listed))
                    && (bestMatch == null || listed.length() > bestMatch.length())) {
                bestMatch = listed;
            }
        }
        return bestMatch != null ? tiers.get(bestMatch) : null;
    }

    private static Map<String, String> loadFixture2026(String fixture) {
        Map<String, String> tiers = new HashMap<>();
        try (InputStream in = new ClassPathResource(fixture).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                String[] parts = line.split(",", 3);
                if (parts.length < 3) continue;
                String name = normalize(stripQuotes(parts[1].trim()));
                String tier = stripQuotes(parts[2].trim()).toUpperCase(Locale.ROOT);
                if (!name.isEmpty() && !tier.isEmpty()) {
                    tiers.put(name, tier);
                }
            }
            log.info("2026 publisher list loaded from {}: {} names", fixture, tiers.size());
        } catch (IOException e) {
            log.error("Failed to load the 2026 publisher list from {}", fixture, e);
        }
        return Map.copyOf(tiers);
    }

    /** The words of the typed name outside the listed one. */
    private static java.util.Set<String> extraTokens(String typed, String listed) {
        java.util.Set<String> extras = new java.util.LinkedHashSet<>(java.util.List.of(typed.split(" ")));
        extras.removeAll(java.util.List.of(listed.split(" ")));
        extras.remove("");
        return extras;
    }

    private static boolean containsWholeTokens(String haystack, String needle) {
        return (" " + haystack + " ").contains(" " + needle + " ");
    }

    private synchronized void tryLoad() {
        if (loaded.get()) {
            return;
        }
        try {
            if (repository.count() == 0) {
                seedFromFixture();
            }
            reloadCache();
            loaded.set(true);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("FSP Psihologie publisher list not loadable (database unreachable): {} — will retry on first use",
                    e.getMessage());
        }
    }

    private void reloadCache() {
        Map<String, String> tiers = new HashMap<>();
        for (PsihologiePublisher p : repository.findAll()) {
            String n = normalize(p.getName());
            if (!n.isEmpty() && p.getTier() != null && !p.getTier().isBlank()) {
                tiers.put(n, p.getTier().trim().toUpperCase(Locale.ROOT));
            }
        }
        tierByName.set(Map.copyOf(tiers));
        log.info("FSP Psihologie publisher list loaded: {} publishers", tiers.size());
    }

    private void seedFromFixture() {
        try (InputStream in = new ClassPathResource(FIXTURE).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<PsihologiePublisher> batch = new ArrayList<>();
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                String[] parts = line.split(",", 3);
                if (parts.length < 3) continue;
                PsihologiePublisher p = new PsihologiePublisher();
                try { p.setNr(Integer.parseInt(parts[0].trim())); } catch (NumberFormatException ignored) { }
                p.setName(stripQuotes(parts[1].trim()));
                p.setTier(stripQuotes(parts[2].trim()));
                batch.add(p);
            }
            repository.saveAll(batch);
            log.info("Seeded FSP Psihologie publisher list from {}: {} rows", FIXTURE, batch.size());
        } catch (IOException e) {
            log.error("Failed to seed FSP Psihologie publisher list from {}", FIXTURE, e);
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized.trim()).replaceAll(" ");
        return normalized.toLowerCase(Locale.ROOT);
    }
}
