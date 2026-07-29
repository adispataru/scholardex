package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.FeaaA2Publisher;
import ro.uvt.pokedex.core.repository.reporting.FeaaA2PublisherRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEAA Anexa 1 b) — "Lista edituri de prestigiu național, conform ... (lista A2)". Backed by the
 * REAL CNATDCU A2 Panel-4 list ({@code report-data/feaa-a2-national-publishers.csv}, sourced from
 * cnatdcu.ro's A2_Panel41.xls) held in its own Mongo collection with the same fixture-reconcile
 * mechanics as {@link FeaaAnexa1PublisherService}: missing fixture rows are inserted on every load
 * (never deleted), so already-seeded databases pick up list growth without a manual import. This
 * replaced the CNCSIS recognized-publishers register that stood in as a proxy until the A2 list was
 * sourced (2026-07-29) — the CNCSIS register remains untouched for its own context (SENSE scoring).
 * Best-effort loading with lazy retry, same failure mode as Anexa 1 (a hard @PostConstruct
 * dependency on Mongo took Quality Gates down once).
 */
@Service
@RequiredArgsConstructor
public class FeaaNationalPublisherService {

    private static final Logger log = LoggerFactory.getLogger(FeaaNationalPublisherService.class);
    private static final String FIXTURE = "report-data/feaa-a2-national-publishers.csv";

    private final FeaaA2PublisherRepository repository;
    private final AtomicReference<Set<String>> normalizedNames = new AtomicReference<>(Set.of());
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    @PostConstruct
    void init() {
        tryLoad();
    }

    /** True when the publisher is on the CNATDCU A2 list (∈ → FEAA national tier). */
    public boolean isRecognized(String publisherName) {
        String normalized = FeaaAnexa1PublisherService.normalize(publisherName);
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
            reconcileFixture();
            reloadCache();
            loaded.set(true);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("FEAA A2 national publisher list not loadable (database unreachable): {} — will retry on first use",
                    e.getMessage());
        }
    }

    private void reloadCache() {
        Set<String> names = new HashSet<>();
        for (FeaaA2Publisher p : repository.findAll()) {
            String n = FeaaAnexa1PublisherService.normalize(p.getName());
            if (!n.isEmpty()) {
                names.add(n);
            }
        }
        normalizedNames.set(Set.copyOf(names));
        log.info("FEAA A2 national publisher list loaded: {} publishers", names.size());
    }

    private void reconcileFixture() {
        try (InputStream in = new ClassPathResource(FIXTURE).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Set<String> existing = new HashSet<>();
            for (FeaaA2Publisher p : repository.findAll()) {
                existing.add(FeaaAnexa1PublisherService.normalize(p.getName()));
            }
            List<FeaaA2Publisher> batch = new ArrayList<>();
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                int comma = line.indexOf(',');
                if (comma < 0) continue;
                FeaaA2Publisher p = new FeaaA2Publisher();
                String nr = line.substring(0, comma).trim();
                try { p.setNr(Integer.parseInt(nr)); } catch (NumberFormatException ignored) { }
                p.setName(stripQuotes(line.substring(comma + 1).trim()));
                if (!existing.contains(FeaaAnexa1PublisherService.normalize(p.getName()))) {
                    batch.add(p);
                }
            }
            if (!batch.isEmpty()) {
                repository.saveAll(batch);
                log.info("Reconciled FEAA A2 national publisher list from {}: {} new rows", FIXTURE, batch.size());
            }
        } catch (IOException e) {
            log.error("Failed to reconcile FEAA A2 national publisher list from {}", FIXTURE, e);
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }
}
