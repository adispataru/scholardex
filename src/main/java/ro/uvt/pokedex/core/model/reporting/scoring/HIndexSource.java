package ro.uvt.pokedex.core.model.reporting.scoring;

/**
 * H67 S4a: which citation universe a Hirsch ({@link ScoringStrategy#HIRSCH}) indicator counts over. The per-publication
 * citation count fed to the h-index reduce is restricted by the citing paper's forum indexing — except
 * {@link #SCHOLARDEX} (source-reported {@code citedByCount} totals) and {@link #GRAPH} (the full internal citation
 * graph), which apply no venue restriction. {@link #SCOPUS_VENUE} / {@link #WOS_VENUE} are <b>indicative</b> (see the
 * H67 plan): computed from our citation graph, not the official Scopus/WoS index.
 */
public enum HIndexSource {
    /** Source-reported totals (the existing "Scholardex h" — {@code citedByCount}). */
    SCHOLARDEX,
    /** Full internal citation graph (no venue restriction). */
    GRAPH,
    /** Citations from Scopus-indexed venues only. */
    SCOPUS_VENUE,
    /** Citations from WoS-indexed venues only. */
    WOS_VENUE;

    /** The legacy persisted-string token for this source (used in the {@code HINDEX_<token>} outputType name). */
    public String token() {
        return switch (this) {
            case SCHOLARDEX -> "SCHOLARDEX";
            case GRAPH -> "GRAPH";
            case SCOPUS_VENUE -> "SCOPUS";
            case WOS_VENUE -> "WOS";
        };
    }

    /** Inverse of {@link #token()}. */
    public static HIndexSource fromToken(String token) {
        return switch (token) {
            case "SCHOLARDEX" -> SCHOLARDEX;
            case "GRAPH" -> GRAPH;
            case "SCOPUS" -> SCOPUS_VENUE;
            case "WOS" -> WOS_VENUE;
            default -> throw new IllegalArgumentException("Unknown HIndexSource token: " + token);
        };
    }
}
