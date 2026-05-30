package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Component
public class ImportSourcePrecedencePolicy {

    private static final Map<String, Integer> PRECEDENCE = Map.of(
            "SCOPUS", 10,
            "SCOPUS_JSON_BOOTSTRAP", 20,
            "SCOPUS_PYTHON_AUTHOR_WORKS", 30,
            "SCOPUS_PYTHON_CITATIONS_PUBLICATION", 30
    );

    public Decision decide(String existingSource, Instant existingImportedAt, String incomingSource, Instant incomingImportedAt) {
        String existing = normalize(existingSource);
        String incoming = normalize(incomingSource);
        if (incoming == null || !PRECEDENCE.containsKey(incoming)) {
            return Decision.REQUIRE_REVIEW;
        }
        if (existing == null) {
            return Decision.APPLY_INCOMING;
        }
        Integer existingRank = PRECEDENCE.get(existing);
        Integer incomingRank = PRECEDENCE.get(incoming);
        if (existingRank == null) {
            return Decision.REQUIRE_REVIEW;
        }
        int rankComparison = Integer.compare(incomingRank, existingRank);
        if (rankComparison > 0) {
            return Decision.APPLY_INCOMING;
        }
        if (rankComparison < 0) {
            return Decision.KEEP_EXISTING;
        }
        if (existingImportedAt != null && incomingImportedAt != null && incomingImportedAt.isAfter(existingImportedAt)) {
            return Decision.APPLY_INCOMING;
        }
        return Decision.KEEP_EXISTING;
    }

    private String normalize(String source) {
        if (source == null) {
            return null;
        }
        String trimmed = source.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    public enum Decision {
        APPLY_INCOMING,
        KEEP_EXISTING,
        REQUIRE_REVIEW
    }
}
