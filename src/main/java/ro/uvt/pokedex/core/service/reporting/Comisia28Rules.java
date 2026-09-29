package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The parameters OM 3.019/2025 sets per domain inside COMISIA 28 (psihologie, științe ale educației, educație
 * fizică și sport). The three domains share one indicator table; what differs is a handful of values, and
 * those are what the scorers read from here instead of carrying their own constants.
 *
 * <p>An indicator opts in through the flag of its domain ({@link Indicator#usesPsihologie2026()},
 * {@link Indicator#usesStiinteEducatiei2026()}). Without a flag the
 * scorers keep the frozen 2016 behaviour, so {@link #of} returning empty means "not a 2026 indicator".</p>
 *
 * <p>Common to every domain of the 2026 annex:</p>
 * <ul>
 *   <li>"Web of Science Core Collection" is spelled out as SCIE, SSCI, AHCI <b>and ESCI</b>, so a journal
 *       that sits in a domain category through its ESCI edition counts like one in SCIE/SSCI
 *       ({@link #withEmergingSources});</li>
 *   <li>an article whose publication year has no impact factor yet takes the last one available.</li>
 * </ul>
 */
public enum Comisia28Rules {

    /** p = 1,00; below p a journal still counts when it is above the median of its category (Q1/Q2). */
    PSIHOLOGIE(1.0, true, Set.of("SCOPUS", "ERIH"), "report-data/psihologie-publishers-2026.csv"),

    /**
     * p = 0,10 and no exception below it. The domain's list of recognised databases is longer than
     * Psychology's; of the additions the platform holds membership data for DOAJ only. CrossRef, JSTOR,
     * CEEOL and Ovid are on the list too and are <b>not</b> applied: read literally "indexed in CrossRef"
     * is true of any journal that registers DOIs, which is a decision for the faculty, not for a default.
     */
    STIINTE_EDUCATIEI(0.10, false, Set.of("SCOPUS", "ERIH", "DOAJ"),
            "report-data/stiinte-educatiei-publishers-2026.csv");

    private final double impactFactorThreshold;
    private final boolean aboveMedianException;
    private final Set<String> recognisedDatabases;
    private final String publisherList;

    Comisia28Rules(double impactFactorThreshold, boolean aboveMedianException,
                   Set<String> recognisedDatabases, String publisherList) {
        this.impactFactorThreshold = impactFactorThreshold;
        this.aboveMedianException = aboveMedianException;
        this.recognisedDatabases = recognisedDatabases;
        this.publisherList = publisherList;
    }

    /** The relevance threshold p for the impact factor (indicators I1, I2, I5, I6). */
    public double impactFactorThreshold() {
        return impactFactorThreshold;
    }

    /** Whether a journal below p still counts at I1/I5 when it is in Q1 or Q2 of its category. */
    public boolean aboveMedianException() {
        return aboveMedianException;
    }

    /**
     * The recognised databases other than Web of Science, restricted to the ones the platform holds
     * membership data for. One of them is enough ("indexate într-una sau mai multe baze de date").
     */
    public Set<String> recognisedDatabases() {
        return recognisedDatabases;
    }

    /** Classpath CSV with the A2/B publisher lists of the domain. */
    public String publisherList() {
        return publisherList;
    }

    /** True when a journal with this impact factor and quartile is counted by the strict indicators I1/I5. */
    public boolean countsOnStrictPath(double impactFactor, String quartile) {
        if (impactFactor >= impactFactorThreshold) {
            return true;
        }
        return aboveMedianException && ("Q1".equals(quartile) || "Q2".equals(quartile));
    }

    public static Optional<Comisia28Rules> of(Indicator indicator) {
        if (indicator == null) {
            return Optional.empty();
        }
        if (indicator.usesPsihologie2026()) {
            return Optional.of(PSIHOLOGIE);
        }
        if (indicator.usesStiinteEducatiei2026()) {
            return Optional.of(STIINTE_EDUCATIEI);
        }
        return Optional.empty();
    }

    /**
     * A copy of the domain that also lists the ESCI edition of each of its categories. The stored domain is
     * left alone on purpose: it is shared with the 2016 report, whose standard predates the edition and
     * whose indicators must keep scoring as they did.
     */
    public static Domain withEmergingSources(Domain domain) {
        if (domain == null || domain.getWosCategories() == null) {
            return domain;
        }
        Set<String> keys = new LinkedHashSet<>(domain.getWosCategories());
        for (String key : domain.getWosCategories()) {
            int delimiter = key == null ? -1 : key.lastIndexOf(" - ");
            if (delimiter > 0) {
                keys.add(key.substring(0, delimiter) + " - ESCI");
            }
        }
        Domain widened = new Domain();
        widened.setId(domain.getId());
        widened.setName(domain.getName());
        widened.setDescription(domain.getDescription());
        List<String> categories = new ArrayList<>(keys);
        widened.setWosCategories(categories);
        return widened;
    }
}
