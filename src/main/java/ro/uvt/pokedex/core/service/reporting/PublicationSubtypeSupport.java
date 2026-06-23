package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;

import java.util.Map;
import java.util.Set;

public final class PublicationSubtypeSupport {

    /**
     * Scopus document subtypes that count as original scientific contributions for CNATDCU scoring:
     * ar=Article, re=Review, cp=Conference Paper, ch=Book Chapter, bk=Book, sh=Short Survey,
     * dp=Data Paper. Any other subtype (editorial "ed", erratum "er", note "no", letter "le",
     * retracted "tb", …) is not a scored contribution — the official standard counts only
     * "rezultate originale" — so it must not carry forum points in any domain.
     */
    public static final Set<String> RESEARCH_SUBTYPES = Set.of("ar", "re", "cp", "ch", "bk", "sh", "dp");

    /**
     * OpenAlex/Crossref work-type vocabulary → Scopus document-subtype codes. Scopus-sourced
     * publications carry the short codes ({@code ar}, {@code re}, …); OpenAlex-sourced ones carry
     * Crossref-style words ({@code article}, {@code book-chapter}, …). Crosswalking here lets the
     * subtype gate and {@link #isSubtype} treat both vocabularies identically — without it tens of
     * thousands of OpenAlex "article" papers silently fail {@link #isResearchContribution} and score
     * zero. Genuinely non-Scopus types (preprint, dissertation, peer-review, paratext, report, …) are
     * intentionally left unmapped so they remain correctly excluded.
     */
    private static final Map<String, String> SUBTYPE_CROSSWALK = Map.ofEntries(
            Map.entry("article", "ar"),
            Map.entry("journal-article", "ar"),
            Map.entry("review", "re"),
            Map.entry("proceedings-article", "cp"),
            Map.entry("conference-paper", "cp"),
            Map.entry("book-chapter", "ch"),
            Map.entry("book-section", "ch"),
            Map.entry("book", "bk"),
            Map.entry("monograph", "bk"),
            Map.entry("reference-book", "bk"),
            Map.entry("dataset", "dp"),
            // Known non-research OpenAlex/Crossref types → Scopus non-research codes (stay gated out).
            Map.entry("editorial", "ed"),
            Map.entry("letter", "le"),
            Map.entry("erratum", "er"),
            Map.entry("correction", "er"));

    private PublicationSubtypeSupport() {
    }

    /**
     * Whether the publication should be scored, i.e. it is not a positively-identified
     * non-research output. Subtypes in {@link #RESEARCH_SUBTYPES} qualify; a missing/blank
     * subtype is given the benefit of the doubt (scored) so sparse metadata does not silently
     * drop points. Only known non-research subtypes (editorial "ed", erratum "er", note "no",
     * letter "le", retracted "tb", …) are excluded.
     */
    public static boolean isResearchContribution(ScoringPublicationReadModel publication) {
        String subtype = resolveSubtype(publication);
        return subtype.isEmpty() || RESEARCH_SUBTYPES.contains(subtype);
    }

    public static String resolveSubtype(ScoringPublicationReadModel publication) {
        if (publication == null) {
            return "";
        }
        String scopusSubtype = normalize(publication.getScopusSubtype());
        String resolved = !scopusSubtype.isEmpty() ? scopusSubtype : normalize(publication.getSubtype());
        return SUBTYPE_CROSSWALK.getOrDefault(resolved, resolved);
    }

    public static boolean isSubtype(ScoringPublicationReadModel publication, String... expected) {
        String subtype = resolveSubtype(publication);
        if (subtype.isEmpty() || expected == null) {
            return false;
        }
        for (String candidate : expected) {
            if (subtype.equals(normalize(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
