package ro.uvt.pokedex.core.service.importing.wos;

import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * H14.1 shared contract helpers. Concrete parser/integration use is deferred to H14.2/H14.4/H14.5.
 */
public final class WosCanonicalContractSupport {

    public static final String FIELD_EDITION_RAW = "editionRaw";
    public static final String FIELD_EDITION_NORMALIZED = "editionNormalized";
    public static final String FIELD_CATEGORY_NAME_CANONICAL = "categoryNameCanonical";
    public static final String FIELD_SOURCE_EVENT_ID = "sourceEventId";
    public static final String FIELD_SOURCE_FILE = "sourceFile";
    public static final String FIELD_SOURCE_VERSION = "sourceVersion";
    public static final String FIELD_SOURCE_ROW_ITEM = "sourceRowItem";

    private static final double SENTINEL_MISSING_SCORE = -999.0;
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private WosCanonicalContractSupport() {
    }

    public static Set<EditionNormalized> normalizeEditionCandidates(String rawEdition) {
        Set<EditionNormalized> editions = new LinkedHashSet<>();
        if (rawEdition == null || rawEdition.isBlank()) {
            editions.add(EditionNormalized.UNKNOWN);
            return editions;
        }

        String normalized = rawEdition.toUpperCase(Locale.ROOT);
        if (normalized.contains("SSCI")) {
            editions.add(EditionNormalized.SSCI);
        }
        if (normalized.contains("SCIE") || normalized.contains("SCIENCE")) {
            editions.add(EditionNormalized.SCIE);
        }
        if (normalized.contains("AHCI")) {
            editions.add(EditionNormalized.AHCI);
        }
        if (normalized.contains("ESCI")) {
            editions.add(EditionNormalized.ESCI);
        }
        if (editions.isEmpty()) {
            editions.add(EditionNormalized.OTHER);
        }
        return editions;
    }

    public static boolean requiresSplitByEdition(String rawEdition) {
        return normalizeEditionCandidates(rawEdition).size() > 1;
    }

    public static Double normalizeMetricValue(Double value) {
        if (value == null) {
            return null;
        }
        return Double.compare(value, SENTINEL_MISSING_SCORE) == 0 ? null : value;
    }

    public static boolean isSourceAllowedForMetric(MetricType metricType, WosSourceType sourceType) {
        if (metricType == null || sourceType == null) {
            return false;
        }
        if (metricType == MetricType.IF) {
            return sourceType == WosSourceType.OFFICIAL_WOS_EXTRACT;
        }
        return true;
    }

    public static WosSourceType selectCanonicalOperationalSource(
            MetricType metricType,
            WosSourceType left,
            WosSourceType right
    ) {
        if (metricType == null) {
            return left != null ? left : right;
        }
        if (metricType == MetricType.AIS || metricType == MetricType.RIS) {
            if (left == WosSourceType.GOV_AIS_RIS || right == WosSourceType.GOV_AIS_RIS) {
                return WosSourceType.GOV_AIS_RIS;
            }
        }
        if (metricType == MetricType.IF) {
            if (left == WosSourceType.OFFICIAL_WOS_EXTRACT || right == WosSourceType.OFFICIAL_WOS_EXTRACT) {
                return WosSourceType.OFFICIAL_WOS_EXTRACT;
            }
        }
        return Objects.requireNonNullElse(left, right);
    }

    public static String normalizeIssnToken(String rawIssn) {
        if (rawIssn == null) {
            return null;
        }
        String normalized = rawIssn.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        return normalized.isBlank() ? null : normalized;
    }

    public static String normalizeTitleFingerprint(String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        String normalized = rawTitle.toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isBlank() ? null : normalized;
    }

    public static String buildIdentityKey(Set<String> normalizedIssnTokens, String normalizedTitleFingerprint, Integer year, String editionRaw) {
        Set<String> issnTokens = normalizedIssnTokens == null ? Set.of() :
                normalizedIssnTokens.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank())
                        .map(v -> v.toUpperCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new));
        if (issnTokens.isEmpty()) {
            return null;
        }
        String joinedIssns = issnTokens.stream().sorted().collect(Collectors.joining("|"));
        String material = "issn:" + joinedIssns;
        return sha256Hex(material);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
