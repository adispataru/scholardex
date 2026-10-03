package ro.uvt.pokedex.core.service.reporting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * H142 slice 4 — the journal databases whose public title lists the platform loads (which journals they index, by
 * ISSN, with coverage years), beside the four it knows from its own sources (Web of Science, Scopus, ERIH PLUS, DOAJ).
 * A database counts only where a standard names it:
 * <ul>
 *   <li><b>Comisia 35, Music</b> — its list of 16: Cambridge Core, CEEOL, DOAJ, EBSCO, ERIH PLUS, JSTOR, Oxford Academic
 *       Journals, Oxford Music Online (a reference work, no journals), Project MUSE, ProQuest, RILM, Sciendo, Scopus,
 *       Taylor &amp; Francis Online, Web of Science AHCI and ESCI (and, by its footnote, the peer-reviewed databases of
 *       related domains for interdisciplinary research);</li>
 *   <li><b>Comisia 25, Sociology</b> — definition [7], some thirty databases; of those with a title list here EBSCO,
 *       ProQuest, CEEOL, JSTOR, Project MUSE and Informa / Tandfonline (Taylor &amp; Francis, one database).</li>
 * </ul>
 * EBSCO and ProQuest are vendors: their subject databases and their general ones (Academic Search Ultimate, ProQuest
 * Central) all count (Adrian, 2026-10-03).
 */
public final class JournalDatabases {

    public static final String CAMBRIDGE_CORE = "CAMBRIDGE_CORE";
    public static final String CEEOL = "CEEOL";
    public static final String EBSCO = "EBSCO";
    public static final String JSTOR = "JSTOR";
    public static final String OXFORD_ACADEMIC = "OXFORD_ACADEMIC";
    public static final String PROJECT_MUSE = "PROJECT_MUSE";
    public static final String PROQUEST = "PROQUEST";
    public static final String RILM = "RILM";
    public static final String SCIENDO = "SCIENDO";
    public static final String TAYLOR_FRANCIS = "TAYLOR_FRANCIS";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(CAMBRIDGE_CORE, "Cambridge Core");
        LABELS.put(CEEOL, "CEEOL");
        LABELS.put(EBSCO, "EBSCO");
        LABELS.put(JSTOR, "JSTOR");
        LABELS.put(OXFORD_ACADEMIC, "Oxford Academic Journals");
        LABELS.put(PROJECT_MUSE, "Project MUSE");
        LABELS.put(PROQUEST, "ProQuest");
        LABELS.put(RILM, "RILM");
        LABELS.put(SCIENDO, "Sciendo");
        LABELS.put(TAYLOR_FRANCIS, "Taylor & Francis Online");
    }

    /** Every database the platform keeps a title list for. */
    public static final Set<String> ALL = Set.copyOf(LABELS.keySet());

    /** Comisia 35, Music: the title-list databases of its list of 16 (the other six the platform knows otherwise). */
    public static final Set<String> MUSIC = ALL;

    /** Comisia 25, Sociology, definition [7]: the ones of its list the platform has a title list for. */
    public static final Set<String> SOCIOLOGY = Set.of(EBSCO, PROQUEST, CEEOL, JSTOR, PROJECT_MUSE, TAYLOR_FRANCIS);

    private JournalDatabases() {
    }

    public static String label(String database) {
        return LABELS.getOrDefault(database, database);
    }

    /** An ISSN as its eight characters, without the hyphen, the check letter upper-case; null when it is no ISSN. */
    public static String normalizeIssn(String value) {
        if (value == null) {
            return null;
        }
        String v = value.replaceAll("[^0-9Xx]", "").toUpperCase(java.util.Locale.ROOT);
        return v.length() == 8 ? v : null;
    }
}
