package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;

import java.util.Locale;

/**
 * DOI-prefix venue signals, mirroring the time-proven {@code puncte/clas.c} classifier. The DOI is an
 * authoritative discriminator independent of the (frequently wrong) forum {@code aggregationType} and
 * the (frequently mislabeled) OpenAlex subtype:
 *
 * <ul>
 *   <li>{@code 10.1007/978…} — a Springer ISBN-based DOI: the item is a book / book-chapter / LNCS-family
 *       proceedings, never a journal article (clas.c {@code springer_ch}).</li>
 * </ul>
 *
 * <p>Note: IEEE's {@code 10.1109} prefix is deliberately NOT exposed as a router — it covers both IEEE
 * journals (Transactions) and proceedings, so in the reference classifier it is only a provenance label
 * ("IEEE PROC") applied after the type is already known to be a conference.
 */
public final class DoiVenueSupport {

    private DoiVenueSupport() {
    }

    /**
     * Whether the DOI is a Springer ISBN-based DOI ({@code 10.1007/978…}) — i.e. a book, book chapter, or
     * LNCS/LNAI/LNBIP proceedings volume. Such an item is never a journal article, whatever the subtype or
     * forum aggregationType claims.
     */
    public static boolean isSpringerBookSeriesProceedings(ScoringPublicationReadModel publication) {
        String path = doiPath(publication);
        return path != null && path.startsWith("10.1007/978");
    }

    /**
     * Whether the DOI is ACM's ({@code 10.1145}). Unlike IEEE's prefix this is only consulted once the item
     * is already known to be a conference contribution, which is what makes it safe: ACM registers both its
     * journals and its proceedings under 10.1145, so the prefix alone does not tell you the venue TYPE — but
     * inside the conference branch the type is settled and the prefix is then an authoritative publisher
     * signal, independent of a forum name that may never mention ACM.
     *
     * <p>Needed because the ACM/EPTCS floor is otherwise detected from the venue NAME, and a DBLP-restamped
     * stream forum carries only the bare acronym: florin.fortis's EuroMLSys 2025 paper sits on a forum named
     * "EUROMLSYS" whose pre-restamp name is "Euromlsys 2025 Proceedings of the 2025 5th Workshop on Machine
     * Learning and Systems" — ACM-published (verified on the DBLP proceedings record, publisher ACM) but the
     * word "ACM" appears in neither name, so a CORE-unranked ACM venue fell to D instead of the C floor.
     */
    public static boolean isAcmPublished(ScoringPublicationReadModel publication) {
        String path = doiPath(publication);
        return path != null && path.startsWith("10.1145/");
    }

    /** The bare DOI path ({@code 10.xxxx/…}), stripping any {@code https://doi.org/} prefix; null if absent. */
    private static String doiPath(ScoringPublicationReadModel publication) {
        if (publication == null || publication.getDoi() == null) {
            return null;
        }
        String doi = publication.getDoi().trim().toLowerCase(Locale.ROOT);
        int idx = doi.indexOf("10.");
        return idx >= 0 ? doi.substring(idx) : null;
    }
}
