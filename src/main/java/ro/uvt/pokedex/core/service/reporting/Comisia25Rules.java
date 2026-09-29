package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/**
 * The parameters OM 3.019/2025 sets per group inside COMISIA 25 (sociologie, științe administrative, științe
 * ale comunicării). The three groups share one indicator table and one set of thresholds; what differs is the
 * list of core and related Web of Science categories (stored as domains) and the column of the A2 publisher
 * list. Only the sociology group is built; the other two are one enum value and one publisher file away.
 *
 * <p>An indicator opts in through the flag of its group ({@link Indicator#usesSociologie2026()}).</p>
 *
 * <p>Common to the whole annex of the commission:</p>
 * <ul>
 *   <li><b>I.1 counts any Web of Science journal that has an impact factor</b>, whatever its category or
 *       edition; the categories only decide the shares asked by criteria C.2 and C.3. The lists of core and
 *       related categories hold humanities categories (History, Philosophy, Religion, …) that exist in AHCI
 *       only, so the edition cannot be a filter here ({@link #anyEdition});</li>
 *   <li>the impact factor is the one of the publication year; an article newer than the last published
 *       impact factors takes the latest one available (definition [9]);</li>
 *   <li>books and chapters count when the publisher has international prestige (list A1) or is on the A2
 *       list of the group (definition [4]).</li>
 * </ul>
 */
public enum Comisia25Rules {

    /** Sociologie, Resurse Umane, Antropologie, Asistență Socială. */
    SOCIOLOGIE("report-data/sociologie-publishers-2026.csv");

    /**
     * The recognised databases (definition [7]) the platform holds membership data for, Web of Science and
     * Scopus aside. The annex lists some thirty; for the others — EBSCO, ProQuest, CEEOL, Index Copernicus,
     * Google Scholar, … — there is nothing to check a journal against.
     */
    public static final Set<String> OTHER_RECOGNISED_DATABASES = Set.of("DOAJ", "ERIH");

    /** How many recognised databases a journal outside Scopus needs (indicator I.2, first row). */
    public static final int DATABASES_REQUIRED = 3;

    private final String publisherList;

    Comisia25Rules(String publisherList) {
        this.publisherList = publisherList;
    }

    /** Classpath CSV with the A2 publishers of the group. */
    public String publisherList() {
        return publisherList;
    }

    public static Optional<Comisia25Rules> of(Indicator indicator) {
        if (indicator != null && indicator.usesSociologie2026()) {
            return Optional.of(SOCIOLOGIE);
        }
        return Optional.empty();
    }

    /**
     * The same domain, read without the SCIE/SSCI filter every other indicator applies: a category key the
     * domain lists counts in the edition it is listed with, and the catch-all domain {@code ALL} admits every
     * edition. The stored domain is not touched; the copy only lives for the duration of one score.
     */
    public static Domain anyEdition(Domain domain) {
        if (domain == null || domain instanceof AnyEditionDomain) {
            return domain;
        }
        AnyEditionDomain copy = new AnyEditionDomain();
        copy.setId(domain.getId());
        copy.setName(domain.getName());
        copy.setDescription(domain.getDescription());
        copy.setWosCategories(domain.getWosCategories() == null
                ? new ArrayList<>() : new ArrayList<>(domain.getWosCategories()));
        return copy;
    }

    /** True when {@code category} belongs to a domain produced by {@link #anyEdition}. */
    public static boolean admits(Domain domain, String category) {
        if (!(domain instanceof AnyEditionDomain) || category == null || category.isBlank()) {
            return false;
        }
        return "ALL".equals(domain.getName())
                || (domain.getWosCategories() != null && domain.getWosCategories().contains(category));
    }

    /** Marker type: a domain whose categories count in every Web of Science edition. Never persisted. */
    static final class AnyEditionDomain extends Domain {
    }
}
