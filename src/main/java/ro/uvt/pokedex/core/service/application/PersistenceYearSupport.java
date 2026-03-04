package ro.uvt.pokedex.core.service.application;

import org.slf4j.Logger;

import java.util.Optional;

public final class PersistenceYearSupport {
    private PersistenceYearSupport() {
    }

    public static Optional<Integer> extractYear(String rawDate, String contextId, Logger log) {
        if (rawDate == null || rawDate.isBlank()) {
            return Optional.empty();
        }

        String normalized = rawDate.trim();
        String yearToken = normalized.length() >= 4 ? normalized.substring(0, 4) : normalized;

        if (!yearToken.chars().allMatch(Character::isDigit)) {
            log.warn("Skipping record with invalid year date '{}' for context '{}'", rawDate, contextId);
            return Optional.empty();
        }

        try {
            return Optional.of(Integer.parseInt(yearToken));
        } catch (NumberFormatException ex) {
            log.warn("Skipping record with unparsable year date '{}' for context '{}'", rawDate, contextId);
            return Optional.empty();
        }
    }

    public static String extractYearString(String rawDate, String contextId, Logger log) {
        return extractYear(rawDate, contextId, log)
                .map(String::valueOf)
                .orElse("");
    }
}
