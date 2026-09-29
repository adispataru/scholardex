package ro.uvt.pokedex.core.model.reporting;

/**
 * H129 — the body whose rules an individual report applies. It decides where the report is found: the
 * sidebar has one entry per authority (CNFIS reporting is a third entry, with sheets instead of reports).
 */
public enum ReportAuthority {
    /** The national minimum standards by domain: every "fișă de verificare". */
    CNATDCU,
    /** Eligibility for the UEFISCDI project competitions (PD, Tinere Echipe). */
    UEFISCDI;

    /** The authority named by a request parameter; CNATDCU when it names none (or none that exists). */
    public static ReportAuthority parse(String value) {
        if (value != null) {
            for (ReportAuthority authority : values()) {
                if (authority.name().equalsIgnoreCase(value.trim())) {
                    return authority;
                }
            }
        }
        return CNATDCU;
    }
}
