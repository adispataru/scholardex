package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.time.LocalDate;
import java.time.YearMonth;
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
    SOCIOLOGIE("report-data/sociologie-publishers-2026.csv", "report-data/sociologie-publishers-2011-panel4.csv");

    /**
     * From when the annex's own A2 list applies — the first day of the academic year 2026–2027, when OM 3.019/2025
     * applies (its art. 6), rather than the day it was published (11 February 2025): Adrian's reading, 2026-10-02.
     * "Cărțile publicate anterior datei intrării în vigoare a prezentei liste și care se aflau pe lista de edituri din
     * Anexa 2" count too: a book that appeared before this day counts at a house of the earlier list.
     */
    public static final LocalDate CURRENT_LIST_FROM = LocalDate.of(2026, 10, 1);

    /**
     * "Lista A1, în vigoare" (definition [4]): CNCS's list of the publishers of international prestige in the social
     * sciences, which the 2011 Social Sciences panel set up (OMECTS 4.691/2011, Anexa 2; 199 houses, UEFISCDI
     * resource-8160) — the key of that list among the international lists ({@link InternationalPublisherSupport}).
     * Read before the stand-ins for international prestige, so a house on it shows the list the standard names.
     */
    public static final String A1_LIST = "CNCS_STIINTE_SOCIALE";

    /**
     * The recognised databases (definition [7]) the platform holds membership data for, Web of Science and
     * Scopus aside. The annex lists some thirty; for the others — EBSCO, ProQuest, CEEOL, Index Copernicus,
     * Google Scholar, … — there is nothing to check a journal against.
     */
    public static final Set<String> OTHER_RECOGNISED_DATABASES = Set.of("DOAJ", "ERIH");

    /** How many recognised databases a journal outside Scopus needs (indicator I.2, first row). */
    public static final int DATABASES_REQUIRED = 3;

    private final String publisherList;
    private final String earlierPublisherList;

    Comisia25Rules(String publisherList, String earlierPublisherList) {
        this.publisherList = publisherList;
        this.earlierPublisherList = earlierPublisherList;
    }

    /** Classpath CSV with the A2 publishers of the group. */
    public String publisherList() {
        return publisherList;
    }

    /**
     * Classpath CSV with the earlier A2 list the annex refers to ("lista de edituri din Anexa 2"): the CNATDCU
     * "Lista A2-Panel 4 – Edituri de prestigiu recunoscut" of the 2011 Social Sciences panel (OMECTS 4.691/2011,
     * Anexa 2 defines it; cnatdcu.ro, A2_Panel41.xls), 45 Romanian and 55 foreign houses. It counts only for a book
     * that appeared before {@link #CURRENT_LIST_FROM}, and never for Lambert Academic Publishing, which it names
     * ({@link ExcludedPublishers}).
     */
    public String earlierPublisherList() {
        return earlierPublisherList;
    }

    /**
     * Whether a book dated so appeared before {@link #CURRENT_LIST_FROM}: an ISO date, a year and month, or a year
     * alone (2026 or earlier counts — the months before October are the larger part). Unknown dates do not.
     */
    public static boolean beforeCurrentList(String date) {
        if (date == null) {
            return false;
        }
        String d = date.trim();
        try {
            if (d.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return LocalDate.parse(d.substring(0, 10)).isBefore(CURRENT_LIST_FROM);
            }
            if (d.matches("\\d{4}-\\d{2}")) {
                return YearMonth.parse(d).atDay(1).isBefore(CURRENT_LIST_FROM);
            }
            if (d.matches("\\d{4}")) {
                return Integer.parseInt(d) <= CURRENT_LIST_FROM.getYear();
            }
        } catch (java.time.DateTimeException e) {
            return false;
        }
        return false;
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
