package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.Domain;

import java.util.List;

public final class ScoringCategorySupport {

    private static final List<String> INDEX_TOKENS = List.of("SCIE", "SSCI");

    /**
     * First JCR metrics year with a unified per-category ranking across editions: from the 2023 data year
     * Clarivate ranks ESCI journals in the same category list as SCIE/SSCI (one rank 1 per category, ESCI
     * ranks interleaved). Before that, an ESCI quartile is computed against a tiny edition-only cohort and
     * is not comparable to a SCIE/SSCI placement.
     */
    public static final int ESCI_UNIFIED_FROM_YEAR = 2023;

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

    public static boolean isEsciIndex(String index) {
        return normalizeCategory(index).contains("ESCI");
    }

    /**
     * Domain-eligibility for an "CATEGORY - ESCI" key: same domain-name check as
     * {@link #isCategoryEligibleForDomain}, but admitting the ESCI edition instead of SCIE/SSCI. Callers
     * that accept ESCI placements (unified-ranking era) combine this with a per-year
     * {@link #ESCI_UNIFIED_FROM_YEAR} check where the resolved year is known.
     */
    public static boolean isEsciCategoryEligibleForDomain(Domain domain, String category) {
        if (domain == null) {
            return false;
        }
        String normalizedCategory = normalizeCategory(category);
        if (normalizedCategory.isEmpty()) {
            return false;
        }
        boolean inDomain = "ALL".equals(domain.getName()) || domain.getWosCategories().contains(category);
        return inDomain && isEsciIndex(extractCategoryIndex(category));
    }
}
