package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;

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
        if (!scopusSubtype.isEmpty()) {
            return scopusSubtype;
        }
        return normalize(publication.getSubtype());
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
