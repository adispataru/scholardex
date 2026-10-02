package ro.uvt.pokedex.core.service.reporting;

import com.opencsv.CSVReader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * H136 — loads the bundled Anexa 7c publisher list ({@code report-data/uefiscdi-anexa7c-publishers-2026.csv},
 * from the PN-IV TE 2026 package, identical in the PD 2026 one) and registers it with
 * {@link UefiscdiPublisherSupport}. Bundled, not database-backed: the list is an annex of a public competition
 * package and changes only with the package.
 */
@Service
public class UefiscdiPublisherListService {

    private static final Logger logger = LoggerFactory.getLogger(UefiscdiPublisherListService.class);
    static final String FIXTURE = "report-data/uefiscdi-anexa7c-publishers-2026.csv";

    @PostConstruct
    void load() {
        UefiscdiPublisherSupport.register(readFixture());
        logger.info("UEFISCDI Anexa 7c publisher list loaded: {} publishers", UefiscdiPublisherSupport.size());
    }

    public static List<String> readFixture() {
        List<String> names = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                new ClassPathResource(FIXTURE).getInputStream(), StandardCharsets.UTF_8))) {
            reader.readNext(); // header: nr,name
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length >= 2 && !row[1].isBlank()) {
                    names.add(row[1].trim());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load the UEFISCDI Anexa 7c publisher list {}: {}", FIXTURE, e.getMessage());
        }
        return names;
    }
}
