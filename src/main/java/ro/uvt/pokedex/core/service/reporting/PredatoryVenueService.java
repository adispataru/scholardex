package ro.uvt.pokedex.core.service.reporting;

import com.opencsv.CSVReader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads Beall's predatory-publisher and standalone-journal lists (checked into {@code data/predatory/}, sourced
 * from the maintained stop-predatory-journals project) and exposes an EXACT-normalized-name predatory check to
 * {@link PredatoryVenueSupport}. Exact match is deliberate: substring matching at this scale is catastrophic
 * (the bare "research" entry alone would false-flag thousands of legitimate venues).
 *
 * <p>An {@link #ALLOWLIST} exempts contested venues the maintained list flags but we choose not to exclude
 * (currently Oncotarget / Impact Journals and WIT Press). The corpus dry-run found 0 UVT-authored papers in any
 * allowlisted venue, so the allowlist is a forward guardrail rather than load-bearing today.</p>
 */
@Service
public class PredatoryVenueService implements PredatoryVenueSupport.PredatoryList {

    private static final Logger logger = LoggerFactory.getLogger(PredatoryVenueService.class);

    /** Contested venues flagged by the maintained list that we do NOT exclude. Normalized names. */
    private static final Set<String> ALLOWLIST = normalizeAll(Set.of(
            "Oncotarget", "Impact Journals", "WIT Press"));

    private final Set<String> predatoryPublishers = new HashSet<>();
    private final Set<String> predatoryJournals = new HashSet<>();

    @Value("${predatory.publishers.file:data/predatory/publishers.csv}")
    private String publishersFile;

    @Value("${predatory.journals.file:data/predatory/journals.csv}")
    private String journalsFile;

    @PostConstruct
    void load() {
        loadLists(publishersFile, journalsFile);
        PredatoryVenueSupport.register(this);
    }

    /** Package-visible for tests: load the two name lists and log a summary. */
    void loadLists(String publishersPath, String journalsPath) {
        predatoryPublishers.clear();
        predatoryJournals.clear();
        loadNames(publishersPath, predatoryPublishers);
        loadNames(journalsPath, predatoryJournals);
        logger.info("Predatory venue list loaded: {} publishers, {} journals, {} allowlisted",
                predatoryPublishers.size(), predatoryJournals.size(), ALLOWLIST.size());
    }

    @Override
    public boolean isPredatory(String forumName, String publisher) {
        String name = PredatoryVenueSupport.normalize(forumName);
        String pub = PredatoryVenueSupport.normalize(publisher);
        if ((!name.isEmpty() && ALLOWLIST.contains(name)) || (!pub.isEmpty() && ALLOWLIST.contains(pub))) {
            return false;
        }
        return (!pub.isEmpty() && predatoryPublishers.contains(pub))
                || (!name.isEmpty() && predatoryJournals.contains(name));
    }

    private void loadNames(String path, Set<String> target) {
        File file = new File(path);
        if (!file.exists()) {
            logger.warn("Predatory list file not found, skipping: {}", path);
            return;
        }
        try (CSVReader reader = new CSVReader(new FileReader(file, StandardCharsets.UTF_8))) {
            reader.readNext(); // header: url,name,abbr
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length >= 2) {
                    String normalized = PredatoryVenueSupport.normalize(row[1]);
                    if (!normalized.isEmpty()) {
                        target.add(normalized);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load predatory list {}: {}", path, e.getMessage());
        }
    }

    private static Set<String> normalizeAll(Set<String> values) {
        Set<String> out = new HashSet<>();
        for (String v : values) {
            String n = PredatoryVenueSupport.normalize(v);
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }
}
