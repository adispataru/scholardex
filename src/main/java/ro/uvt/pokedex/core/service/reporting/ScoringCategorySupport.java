package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.Domain;

import java.util.List;

public final class ScoringCategorySupport {

    private static final List<String> INDEX_TOKENS = List.of("SCIE", "SSCI");

    private ScoringCategorySupport() {
    }

    public static String normalizeCategory(String category) {
        return category == null ? "" : category.trim();
    }

    public static String extractCategoryName(String category) {
        String normalized = normalizeCategory(category);
        if (normalized.isEmpty()) {
            return "";
        }
        int delimiter = normalized.lastIndexOf('-');
        if (delimiter < 0) {
            return normalized;
        }
        return normalized.substring(0, delimiter).trim();
    }

    public static String extractCategoryIndex(String category) {
        String normalized = normalizeCategory(category);
        if (normalized.isEmpty()) {
            return "";
        }
        int delimiter = normalized.lastIndexOf('-');
        if (delimiter < 0 || delimiter == normalized.length() - 1) {
            return "";
        }
        return normalized.substring(delimiter + 1).trim();
    }

    public static boolean isScieOrSsciIndex(String index) {
        String normalizedIndex = normalizeCategory(index);
        if (normalizedIndex.isEmpty()) {
            return false;
        }
        for (String token : INDEX_TOKENS) {
            if (normalizedIndex.contains(token)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCategoryEligibleForDomain(Domain domain, String category) {
        if (domain == null) {
            return false;
        }
        String normalizedCategory = normalizeCategory(category);
        if (normalizedCategory.isEmpty()) {
            return false;
        }
        boolean inDomain = "ALL".equals(domain.getName()) || domain.getWosCategories().contains(category);
        if (!inDomain) {
            return false;
        }
        return isScieOrSsciIndex(extractCategoryIndex(category));
    }
}
