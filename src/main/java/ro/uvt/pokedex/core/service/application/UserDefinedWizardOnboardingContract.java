package ro.uvt.pokedex.core.service.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * H21.1 locked contract helper for USER_DEFINED wizard onboarding naming and deterministic keying.
 * Runtime migration to consume this contract is implemented in H21.2+.
 */
public final class UserDefinedWizardOnboardingContract {

    public static final String SOURCE = "USER_DEFINED";
    public static final String FORUM_SOURCE_RECORD_PREFIX = "USER_DEFINED:FORUM:";
    public static final String PUBLICATION_SOURCE_RECORD_PREFIX = "USER_DEFINED:PUBLICATION:";
    /**
     * H99 item 7: id prefix for wizard-minted book entities ({@code scholardex.book_facts} rows with
     * {@code source=USER_DEFINED}). Books are a distinct entity from forums (H66B M7) — a wizard book or
     * chapter resolves a {@code bookId} against the book list, and only a book absent from the Scopus
     * snapshot (Mirton, Eubeea, …) mints one of these. Deterministic so a resubmission reuses the entity.
     */
    public static final String BOOK_ID_PREFIX = "USER_DEFINED:BOOK:";

    public static final List<String> LINEAGE_FIELDS = List.of(
            "source",
            "sourceRecordId",
            "sourceEventId",
            "sourceBatchId",
            "sourceCorrelationId"
    );

    public static final String REVIEW_STATE = "PENDING_OPERATOR_REVIEW";
    public static final String MODERATION_FLOW = "METADATA_ONLY_NO_SEPARATE_APPROVAL_UI";

    private static final int HASH_LEN = 24;

    private UserDefinedWizardOnboardingContract() {
    }

    public static String deterministicForumSourceRecordId(
            String publicationName,
            String issn,
            String eIssn,
            String aggregationType
    ) {
        String normalizedIssn = normalizeIssnOrBlank(issn);
        String normalizedEIssn = normalizeIssnOrBlank(eIssn);
        String keyMaterial;
        if (!isBlank(normalizedIssn) || !isBlank(normalizedEIssn)) {
            keyMaterial = "issn|" + normalizedIssn + "|" + normalizedEIssn;
        } else {
            keyMaterial = "name|"
                    + normalizeToken(publicationName)
                    + "|type|"
                    + normalizeToken(aggregationType);
        }
        return FORUM_SOURCE_RECORD_PREFIX + shortHash(keyMaterial);
    }

    public static String deterministicPublicationSourceRecordId(
            String doi,
            String title,
            String coverDate,
            String creator,
            String forumSourceRecordId
    ) {
        String normalizedDoi = normalizeDoi(doi);
        String keyMaterial;
        if (!isBlank(normalizedDoi)) {
            keyMaterial = "doi|" + normalizedDoi;
        } else {
            keyMaterial = "title|" + normalizeToken(title)
                    + "|date|" + normalizeDate(coverDate)
                    + "|creator|" + normalizeToken(creator)
                    + "|forum|" + normalizeToken(forumSourceRecordId);
        }
        return PUBLICATION_SOURCE_RECORD_PREFIX + shortHash(keyMaterial);
    }

    /**
     * Deterministic id for a wizard-minted book entity: ISBN-keyed when an ISBN is given (the stable
     * bibliographic identity), else normalized title + publisher. Mirrors the forum helper's
     * identifier-first shape so the same book entered twice lands on one {@code book_facts} row.
     */
    public static String deterministicBookId(String title, String isbn, String publisher) {
        String normalizedIsbn = trim(isbn).replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
        String keyMaterial;
        if (!isBlank(normalizedIsbn)) {
            keyMaterial = "isbn|" + normalizedIsbn;
        } else {
            keyMaterial = "title|" + normalizeToken(title) + "|publisher|" + normalizeToken(publisher);
        }
        return BOOK_ID_PREFIX + shortHash(keyMaterial);
    }

    static String normalizeDoi(String rawDoi) {
        String value = trim(rawDoi);
        if (isBlank(value)) {
            return "";
        }
        return value
                .replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "")
                .replaceFirst("(?i)^doi:", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    static String normalizeIssnOrBlank(String rawIssn) {
        String value = trim(rawIssn).replace("-", "").toUpperCase(Locale.ROOT);
        if (isBlank(value)) {
            return "";
        }
        if (value.length() != 8) {
            return "";
        }
        return value.substring(0, 4) + "-" + value.substring(4);
    }

    static String normalizeDate(String rawDate) {
        String value = trim(rawDate);
        if (isBlank(value)) {
            throw new IllegalArgumentException("Cover date is required.");
        }
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Cover date must be ISO format YYYY-MM-DD.");
        }
    }

    static String normalizeToken(String rawValue) {
        return trim(rawValue).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String shortHash(String rawValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawValue.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hashBytes.length * 2);
            for (byte hashByte : hashBytes) {
                out.append(String.format("%02x", hashByte));
            }
            return out.substring(0, HASH_LEN);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
