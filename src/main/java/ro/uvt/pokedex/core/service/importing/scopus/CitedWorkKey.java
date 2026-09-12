package ro.uvt.pokedex.core.service.importing.scopus;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

/**
 * H106 S5 — how a Scopus citation fact names its CITED side when the cited work is not a Scopus document.
 * <p>
 * {@code ScopusCitationFact.citedEid} historically holds a Scopus EID. The reverse-title search also finds
 * citations of works Scopus never indexed (arXiv items reached through OpenAlex), which have no EID. Rather
 * than widen the fact (and its unique edge index), the same field carries one of three key shapes:
 * <ul>
 *   <li>{@code 2-s2.0-…} — a Scopus EID, resolved through the publication fact's {@code eid} (unchanged);</li>
 *   <li>{@code doi:<normalized doi>} — resolved through {@code doiNormalized}; stable across full rebuilds
 *       because the V2 canonical id itself derives from the DOI;</li>
 *   <li>{@code spub_…} — the canonical publication id, for DOI-less works (user-defined entries);
 *       resolved directly, stable as long as the id's derivation material does not change.</li>
 * </ul>
 * The citing side is always a Scopus document found by the search, so it stays an EID.
 */
public final class CitedWorkKey {

    public static final String DOI_PREFIX = "doi:";
    public static final String CANONICAL_PREFIX = "spub_";

    private CitedWorkKey() {
    }

    /** The key for a cited work: its EID when it has one, else its normalized DOI, else its canonical id. */
    public static String forPublication(ScholardexPublicationView publication) {
        if (publication == null) return null;
        if (publication.getEid() != null && !publication.getEid().isBlank()) {
            return publication.getEid().trim();
        }
        String doi = ScholardexPublicationCanonicalizationService.normalizeDoi(publication.getDoi());
        if (doi != null) {
            return DOI_PREFIX + doi;
        }
        return publication.getId() != null && !publication.getId().isBlank() ? publication.getId().trim() : null;
    }

    public static boolean isDoiKey(String key) {
        return key != null && key.startsWith(DOI_PREFIX) && key.length() > DOI_PREFIX.length();
    }

    public static boolean isCanonicalKey(String key) {
        return key != null && key.startsWith(CANONICAL_PREFIX);
    }

    /** The normalized DOI behind a {@code doi:} key, or null for any other shape. */
    public static String doiOf(String key) {
        return isDoiKey(key) ? key.substring(DOI_PREFIX.length()) : null;
    }
}
