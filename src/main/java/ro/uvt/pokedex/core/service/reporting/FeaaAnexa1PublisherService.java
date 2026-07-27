package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.FeaaAnexa1Publisher;
import ro.uvt.pokedex.core.repository.reporting.FeaaAnexa1PublisherRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Loads and matches the FEAA Anexa 1 prestige-publisher allowlist. Mirrors the CNCSIS publisher list
 * (a flat name-matched list), but self-seeds the Mongo collection from a bundled CSV fixture
 * ({@code report-data/feaa-anexa1-publishers.csv}) when empty, so no manual import step is required.
 * Membership is answered against a normalized-name set so spelling/diacritic variants still match.
 */
@Service
@RequiredArgsConstructor
public class FeaaAnexa1PublisherService {

    private static final Logger log = LoggerFactory.getLogger(FeaaAnexa1PublisherService.class);
    private static final String FIXTURE = "report-data/feaa-anexa1-publishers.csv";
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private final FeaaAnexa1PublisherRepository repository;
    private final AtomicReference<Set<String>> normalizedNames = new AtomicReference<>(Set.of());
    private final java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Best-effort load at startup. Mongo may not be reachable yet (context-load smoke tests run
     * without a database; on the cluster the app pod can start before Mongo) — a hard failure here
     * took the whole context down (Quality Gates red since 2026-05-06). Unavailable → warn and
     * retry lazily on first lookup instead.
     */
    @PostConstruct
    void init() {
        tryLoad();
    }

    /** True when the publisher is in the Anexa 1 prestige list (∈ → international tier). */
    public boolean isPrestigePublisher(String publisherName) {
        String normalized = normalize(publisherName);
        if (normalized.isEmpty()) {
            return false;
        }
        if (!loaded.get()) {
            tryLoad(); // self-heals once the database is reachable
        }
        return normalizedNames.get().contains(normalized);
    }

    private synchronized void tryLoad() {
        if (loaded.get()) {
            return;
        }
        try {
            // Reconcile instead of seed-only-when-empty: a fixture row missing from the collection
            // (e.g. De Gruyter, added by the 2026 Anexa 1) is inserted on every load, so already-seeded
            // databases — including prod — pick up list growth without a manual import. Never deletes.
            reconcileFixture();
            reloadCache();
            loaded.set(true);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("FEAA Anexa 1 publisher list not loadable (database unreachable): {} — will retry on first use",
                    e.getMessage());
        }
    }

    private void reloadCache() {
        Set<String> names = new HashSet<>();
        for (FeaaAnexa1Publisher p : repository.findAll()) {
            String n = normalize(p.getName());
            if (!n.isEmpty()) {
                names.add(n);
            }
        }
        normalizedNames.set(Set.copyOf(names));
        log.info("FEAA Anexa 1 publisher list loaded: {} publishers", names.size());
    }

    private void reconcileFixture() {
        try (InputStream in = new ClassPathResource(FIXTURE).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Set<String> existing = new HashSet<>();
            for (FeaaAnexa1Publisher p : repository.findAll()) {
                existing.add(normalize(p.getName()));
            }
            List<FeaaAnexa1Publisher> batch = new ArrayList<>();
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                int comma = line.indexOf(',');
                if (comma < 0) continue;
                FeaaAnexa1Publisher p = new FeaaAnexa1Publisher();
                String nr = line.substring(0, comma).trim();
                try { p.setNr(Integer.parseInt(nr)); } catch (NumberFormatException ignored) { }
                p.setName(stripQuotes(line.substring(comma + 1).trim()));
                if (!existing.contains(normalize(p.getName()))) {
                    batch.add(p);
                }
            }
            if (!batch.isEmpty()) {
                repository.saveAll(batch);
                log.info("Reconciled FEAA Anexa 1 publisher list from {}: {} new rows", FIXTURE, batch.size());
            }
        } catch (IOException e) {
            log.error("Failed to reconcile FEAA Anexa 1 publisher list from {}", FIXTURE, e);
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
