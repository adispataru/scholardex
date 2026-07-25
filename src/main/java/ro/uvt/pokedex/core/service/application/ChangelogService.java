package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.application.model.ChangelogEntry;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * H86 — loads the committed changelog fixture at startup and serves it filtered for the viewer.
 *
 * <p>Fail-soft on purpose (the {@code FeaaAnexa1PublisherService} precedent): a malformed or missing fixture logs
 * a warning and yields an empty log rather than taking the context down — a changelog must never be able to break
 * the app it documents.</p>
 */
@Service
public class ChangelogService {

    private static final Logger log = LoggerFactory.getLogger(ChangelogService.class);
    private static final String FIXTURE = "changelog/changelog.json";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicReference<List<ChangelogEntry>> entries = new AtomicReference<>(List.of());

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(FIXTURE).getInputStream()) {
            List<ChangelogEntry> parsed = objectMapper.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            List<ChangelogEntry> sorted = parsed.stream()
                    .filter(entry -> entry.date() != null && entry.title() != null)
                    .sorted(Comparator.comparing(ChangelogEntry::date).reversed())
                    .toList();
            entries.set(sorted);
            log.info("Changelog loaded: {} entries ({} scoring-impact), newest {}",
                    sorted.size(),
                    sorted.stream().filter(ChangelogEntry::scoringImpact).count(),
                    sorted.isEmpty() ? "n/a" : sorted.getFirst().date());
        } catch (IOException | RuntimeException ex) {
            log.warn("Changelog fixture {} unavailable or malformed — serving an empty changelog: {}",
                    FIXTURE, ex.getMessage());
            entries.set(List.of());
        }
    }

    /** Entries the viewer may see, newest first. */
    public List<ChangelogEntry> entriesFor(boolean viewerIsAdmin) {
        return entries.get().stream()
                .filter(entry -> entry.audience().visibleTo(viewerIsAdmin))
                .toList();
    }

    /** Newest-first entries grouped by date, for a date-headed page. Keeps the insertion order of the group keys. */
    public Map<LocalDate, List<ChangelogEntry>> groupedByDate(boolean viewerIsAdmin) {
        Map<LocalDate, List<ChangelogEntry>> grouped = new LinkedHashMap<>();
        for (ChangelogEntry entry : entriesFor(viewerIsAdmin)) {
            grouped.computeIfAbsent(entry.date(), ignored -> new java.util.ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /** How many visible entries are newer than the reader's last visit — drives the "what's new" badge. */
    public long newSince(java.time.Instant lastVisit, boolean viewerIsAdmin) {
        if (lastVisit == null) {
            return 0; // a first-time reader is not "behind" on anything
        }
        LocalDate since = lastVisit.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return entriesFor(viewerIsAdmin).stream().filter(entry -> entry.date().isAfter(since)).count();
    }

    public LocalDate latestDate() {
        List<ChangelogEntry> all = entries.get();
        return all.isEmpty() ? null : all.getFirst().date();
    }
}
