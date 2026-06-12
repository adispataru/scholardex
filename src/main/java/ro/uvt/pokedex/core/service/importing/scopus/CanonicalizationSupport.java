package ro.uvt.pokedex.core.service.importing.scopus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared utility methods and constants for the canonicalization pipeline.
 * Eliminates duplication across the four entity-specific canonicalization services.
 */
public final class CanonicalizationSupport {

    public static final String STATUS_OPEN = "OPEN";
    public static final String DEFAULT_SOURCE_VERSION_PREFIX = "scopus-";
    static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private CanonicalizationSupport() {
    }

    public static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "" : normalized;
    }

    public static String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    // H56: SHA-256 hashing runs ~2.5M times per pipeline rebuild (deterministic ids, payload hashes).
    // Reuse the digest per thread and hex-encode via table lookup instead of String.format("%02x")
    // per byte — byte-identical output, ~10x faster.
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    });

    /** Full lowercase-hex SHA-256 of the value (64 chars). */
    public static String sha256Hex(String value) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        char[] out = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int b = hash[i] & 0xFF;
            out[i * 2] = HEX_DIGITS[b >>> 4];
            out[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(out);
    }

    public static String shortHash(String value) {
        return sha256Hex(value).substring(0, 24);
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static int normalizeStartBatch(Integer startBatchOverride, int checkpointLastCompletedBatch, boolean useCheckpoint) {
        if (startBatchOverride != null) {
            return Math.max(0, startBatchOverride);
        }
        if (useCheckpoint && checkpointLastCompletedBatch >= 0) {
            return Math.max(0, checkpointLastCompletedBatch + 1);
        }
        return 0;
    }

    public static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    public static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public static void addUnique(List<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!values.contains(value)) {
            values.add(value);
        }
    }
}
