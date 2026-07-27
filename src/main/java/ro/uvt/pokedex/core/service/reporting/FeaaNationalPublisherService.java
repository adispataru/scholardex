package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CNCSISPublisher;
import ro.uvt.pokedex.core.repository.reporting.CNCSISPublisherRepository;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * FEAA 2026 Anexa 1 b) — "Lista edituri de prestigiu național". The standard references the CNATDCU
 * social-science A2 list (cnatdcu.ro/metodologie/abilitare), which is not bundled in this repo; the
 * CNCSIS recognized-publishers register (already loaded for SENSE scoring) stands in as the national
 * membership until the A2 list is sourced — a documented approximation, matched by normalized name
 * exactly like {@link FeaaAnexa1PublisherService}. Best-effort loading with lazy retry, same failure
 * mode as that service (a hard @PostConstruct dependency on Mongo took Quality Gates down once).
 */
@Service
@RequiredArgsConstructor
public class FeaaNationalPublisherService {

    private static final Logger log = LoggerFactory.getLogger(FeaaNationalPublisherService.class);
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private final CNCSISPublisherRepository repository;
    private final AtomicReference<Set<String>> normalizedNames = new AtomicReference<>(Set.of());
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    @PostConstruct
    void init() {
        tryLoad();
    }

    /** True when the publisher is on the national recognized-publishers register (∈ → national tier). */
    public boolean isRecognized(String publisherName) {
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
            Set<String> names = new HashSet<>();
            for (CNCSISPublisher publisher : repository.findAll()) {
                String normalized = normalize(publisher.getName());
                if (!normalized.isEmpty()) {
                    names.add(normalized);
                }
            }
            normalizedNames.set(Set.copyOf(names));
            loaded.set(true);
            log.info("FEAA national publisher register loaded: {} publishers", names.size());
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("FEAA national publisher register not loadable (database unreachable): {} — will retry on first use",
                    e.getMessage());
        }
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
        String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String alnum = NON_ALNUM_OR_SPACE.matcher(stripped).replaceAll(" ");
        return MULTI_SPACE.matcher(alnum).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }
}
