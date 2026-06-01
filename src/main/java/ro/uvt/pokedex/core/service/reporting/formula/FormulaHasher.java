package ro.uvt.pokedex.core.service.reporting.formula;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * H52 slice 5: SHA-256 of the canonical formula form. Hex-encoded, lowercase.
 *
 * <p>Used for two distinct identities:</p>
 * <ol>
 *   <li>{@code Indicator.formulaHash} persisted at save time — the {@code userIndicatorResults}
 *       cache fingerprint will key on this in commit 3, so two indicators that share the
 *       same canonical formula share the same cached score.</li>
 *   <li>{@link FormulaEvaluator}'s compile-cache key — slice 5 swaps from "rewritten
 *       expression string" to "hash". Cheaper to compute than to keep the full expression
 *       around, and de-duplicates whitespace-different but semantically-equal formulas.</li>
 * </ol>
 *
 * <p>Stability contract: same canonical form → same hash, forever. {@link FormulaCanonicalizer}
 * documents what "same canonical form" actually means; in short, cosmetic whitespace and
 * line-comment edits are stable, semantic edits (operator changes, literal types) are not.</p>
 */
public final class FormulaHasher {

    private FormulaHasher() {}

    /**
     * Hashes {@code rawFormula} by first {@link FormulaCanonicalizer#canonicalize canonicalizing}
     * it, then SHA-256 over UTF-8 bytes, hex-encoded lowercase.
     *
     * @throws NullPointerException if {@code rawFormula} is null
     * @throws IllegalArgumentException if {@code rawFormula} is blank
     */
    public static String hash(String rawFormula) {
        String canonical = FormulaCanonicalizer.canonicalize(rawFormula);
        return hashCanonical(canonical);
    }

    /**
     * Variant for callers that already canonicalized — avoids the second pass. Hot path
     * for {@link FormulaEvaluator} which canonicalizes once and reuses both the string
     * and its hash.
     */
    public static String hashCanonical(String canonical) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JCA implementation; this is unreachable.
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
        byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
