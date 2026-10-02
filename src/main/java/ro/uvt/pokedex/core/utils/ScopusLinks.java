package ro.uvt.pokedex.core.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H119 — link from a record that carries Scopus metadata to that record's page on Scopus, as Elsevier's
 * attribution rules ask. The EID is {@code 2-s2.0-<scp>}; the inward link takes the {@code scp} part.
 */
public final class ScopusLinks {

    private static final Pattern EID = Pattern.compile("^2-s2\\.0-(\\d+)$");

    private ScopusLinks() {
    }

    /**
     * H131: the cited-by list of a record on Scopus, or null when the value is not a Scopus EID — Elsevier's
     * attribution rule asks that a displayed Scopus citation count link to it.
     */
    public static String citedByUrl(String eid) {
        if (eid == null) return null;
        Matcher m = EID.matcher(eid.trim());
        return m.matches()
                ? "https://www.scopus.com/inward/citedby.uri?partnerID=HzOxMe3b&scp=" + m.group(1) + "&origin=inward"
                : null;
    }

    /** The record page on Scopus, or null when the value is not a Scopus EID. */
    public static String recordUrl(String eid) {
        if (eid == null) return null;
        Matcher m = EID.matcher(eid.trim());
        return m.matches()
                ? "https://www.scopus.com/inward/record.uri?partnerID=HzOxMe3b&scp=" + m.group(1) + "&origin=inward"
                : null;
    }
}
