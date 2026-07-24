package ro.uvt.pokedex.core.service.importing.scopus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H84 — static merged-publication alias lookup, the resurrection guard for incremental syncs. When a merge is
 * applied, the duplicate's canonical id and its source-record refs are registered here pointing at the survivor;
 * the on-demand canonicalization paths consult the registry before writing a publication fact, so a re-synced
 * source record resolves onto the survivor instead of re-minting the deleted duplicate.
 *
 * <p>Static-registry wiring (precedent: {@code PredatoryVenueSupport}) keeps the two canonicalization services'
 * constructors unchanged. {@code PublicationMergeService} owns the content: it loads all APPROVED decisions at
 * startup and refreshes entries whenever decisions change or merges apply. Empty in unit tests unless seeded;
 * {@link #clear()} resets state between tests.</p>
 */
public final class PublicationMergeAliasRegistry {

    private static final Map<String, String> SURVIVOR_BY_CANONICAL_ID = new ConcurrentHashMap<>();
    private static final Map<String, String> SURVIVOR_BY_SOURCE_REF = new ConcurrentHashMap<>();

    private PublicationMergeAliasRegistry() {
    }

    public static void register(String duplicateCanonicalId, Iterable<String> duplicateSourceRefs, String survivorCanonicalId) {
        if (survivorCanonicalId == null || survivorCanonicalId.isBlank()) {
            return;
        }
        if (duplicateCanonicalId != null && !duplicateCanonicalId.isBlank()
                && !duplicateCanonicalId.equals(survivorCanonicalId)) {
            SURVIVOR_BY_CANONICAL_ID.put(duplicateCanonicalId, survivorCanonicalId);
        }
        if (duplicateSourceRefs != null) {
            for (String ref : duplicateSourceRefs) {
                if (ref != null && !ref.isBlank()) {
                    SURVIVOR_BY_SOURCE_REF.put(normalizeRef(ref), survivorCanonicalId);
                }
            }
        }
    }

    public static void unregister(String duplicateCanonicalId, Iterable<String> duplicateSourceRefs) {
        if (duplicateCanonicalId != null) {
            SURVIVOR_BY_CANONICAL_ID.remove(duplicateCanonicalId);
        }
        if (duplicateSourceRefs != null) {
            for (String ref : duplicateSourceRefs) {
                if (ref != null) {
                    SURVIVOR_BY_SOURCE_REF.remove(normalizeRef(ref));
                }
            }
        }
    }

    /**
     * Resolve a would-be canonical id through the alias chain (transitive, cycle-safe). Returns the input id when
     * no alias applies — callers can use the result unconditionally.
     */
    public static String resolveCanonicalId(String canonicalId) {
        String current = canonicalId;
        for (int hop = 0; current != null && hop < 10; hop++) {
            String next = SURVIVOR_BY_CANONICAL_ID.get(current);
            if (next == null || next.equals(current)) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /** Survivor id for a merged source record ({@code SOURCE:recordId}), or null when the record is not merged. */
    public static String survivorForSourceRecord(String source, String sourceRecordId) {
        if (source == null || sourceRecordId == null) {
            return null;
        }
        String direct = SURVIVOR_BY_SOURCE_REF.get(normalizeRef(source + ":" + sourceRecordId));
        return direct == null ? null : resolveCanonicalId(direct);
    }

    public static void clear() {
        SURVIVOR_BY_CANONICAL_ID.clear();
        SURVIVOR_BY_SOURCE_REF.clear();
    }

    private static String normalizeRef(String ref) {
        return ref.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
