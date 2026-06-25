package ro.uvt.pokedex.core.service.importing.wos;

/**
 * H76: one WoS Core Collection proceedings record from the CPCI Records export
 * ({@code data/wos/cpci/*.csv}, columns ut,doi,sourceTitle,conferenceTitle,bookSeriesTitle,issn,eIssn,isbn,year).
 * These are WoS-indexed conference papers; onboarding tags the forum each one maps to as WoS-indexed.
 */
public record WosCpciRecord(
        String ut,
        String doi,
        String sourceTitle,
        String conferenceTitle,
        String bookSeriesTitle,
        String issn,
        String eIssn,
        String isbn,
        String year
) {
    /** The most specific venue label for display/title matching — conference title, else source title. */
    public String venueLabel() {
        if (conferenceTitle != null && !conferenceTitle.isBlank()) {
            return conferenceTitle;
        }
        return sourceTitle;
    }
}
