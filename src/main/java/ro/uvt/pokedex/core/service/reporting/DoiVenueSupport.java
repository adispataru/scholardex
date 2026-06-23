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
