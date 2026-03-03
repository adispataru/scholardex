package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.scopus.Publication;

public final class PublicationSubtypeSupport {

    private PublicationSubtypeSupport() {
    }

    public static String resolveSubtype(Publication publication) {
        if (publication == null) {
            return "";
        }
        String scopusSubtype = normalize(publication.getScopusSubtype());
        if (!scopusSubtype.isEmpty()) {
            return scopusSubtype;
        }
        return normalize(publication.getSubtype());
    }

    public static boolean isSubtype(Publication publication, String... expected) {
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
