package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.ArtisticEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One declared artistic performance ("Participare eveniment artistic") serves two reports: the CNATDCU
 * Music standard (OM 3.019/2025, Comisia 35 — concerts by visibility, prizes at competitions) and the CNFIS
 * sheet of artistic creation (Anexa 5.1 — the kind of project and the level of the event). The person picks
 * the event, the role, the size of the ensemble and the result; everything else is derived here, so neither
 * report asks for it twice (H142).
 *
 * <p>Field names and their allowed values are those of the activity type; a record made before H142 carries
 * only the CNFIS "Tip" and keeps working.</p>
 */
public final class ArtisticPerformanceSupport {

    public static final String FIELD_ROLE = "Rol";
    public static final String FIELD_ENSEMBLE_SIZE = "Marime_formatie";
    public static final String FIELD_RESULT = "Rezultat";
    /** The CNFIS kind as declared before H142 (Proiect individual / de grup / colectiv, Nominalizare, Premiu). */
    public static final String FIELD_CNFIS_KIND = "Tip";

    public static final String ROLE_LARGE_ENSEMBLE_MEMBER = "Membru într-un ansamblu de peste 10 persoane";
    public static final String ROLE_OTHER = "Alt rol";

    /** Formula values of {@code Rezultat_eveniment}. */
    public static final String PARTICIPATION = "PARTICIPARE";
    public static final String NOMINATION = "NOMINALIZARE";
    public static final String PRIZE = "PREMIU";

    private ArtisticPerformanceSupport() {
    }

    /**
     * What the record is: a prize, a nomination, or a participation (the default), as its result states. H145: the
     * CNFIS kind a record may still carry is never read — it was a pick that turned an orchestra concert into an
     * individual prize.
     */
    public static String result(Map<String, String> fields) {
        String declared = lower(fields.get(FIELD_RESULT));
        if (declared.startsWith("premiu")) return PRIZE;
        if (declared.startsWith("nominalizare")) return NOMINATION;
        return PARTICIPATION;
    }

    /**
     * Whether the role counts for the Music standard's concerts: composer, conductor, director, ballet master,
     * soloist, concertmaster or member of a chamber ensemble of at most ten. A member of a larger ensemble or
     * another role does not; a record without a role counts — listing a concert is the candidate's own
     * declaration, and an imported grid row names no role.
     */
    public static boolean roleCounts(Map<String, String> fields) {
        String role = fields.get(FIELD_ROLE);
        if (role == null || role.isBlank()) {
            return false; // H145: a role the record does not state does not count
        }
        String r = role.trim();
        if (r.toLowerCase(Locale.ROOT).startsWith("membru într-o formație camerală")) {
            Integer size = parseSize(fields.get(FIELD_ENSEMBLE_SIZE));
            if (size != null && size > CHAMBER_MAX) {
                return false; // H145: a chamber ensemble has at most ten members — a larger one is not one
            }
        }
        return !ROLE_LARGE_ENSEMBLE_MEMBER.equalsIgnoreCase(r) && !ROLE_OTHER.equalsIgnoreCase(r);
    }

    /** The most members a chamber ensemble has (the Music standard, CS 1). */
    static final int CHAMBER_MAX = 10;

    /**
     * How the visibility of a concert was decided, for the drilldown: the registry's rank; an event the experts have
     * not ranked (unknown, proposed, or rejected as a name); no event named.
     */
    public enum VisibilityBasis { REGISTRY, AWAITING_RANK, NO_EVENT }

    public record Visibility(boolean top, VisibilityBasis basis) {
    }

    /**
     * Top visibility ("internațională sau națională de vârf", CS 1.1) or regional/local (CS 1.2), from the rank the
     * registry gives the event — top for a top-international, international or national-top event (H142 slice 3). An
     * event the experts have not ranked counts as regional/local, the floor the standard gives any eligible public
     * performance, until they do; nobody picks the visibility of their own concert.
     */
    public static Visibility visibility(Optional<ArtisticEvent.Rank> rank, boolean eventNamed) {
        if (rank.isPresent()) {
            return new Visibility(isTop(rank.get()), VisibilityBasis.REGISTRY);
        }
        return new Visibility(false, eventNamed ? VisibilityBasis.AWAITING_RANK : VisibilityBasis.NO_EVENT);
    }

    /** Whether the rank gives "vizibilitate internațională sau națională de vârf". */
    public static boolean isTop(ArtisticEvent.Rank rank) {
        return rank == ArtisticEvent.Rank.INTERNATIONAL_TOP || rank == ArtisticEvent.Rank.INTERNATIONAL
                || rank == ArtisticEvent.Rank.NATIONAL_TOP;
    }

    /**
     * The CNFIS kind of Anexa 5.1 — INDIVIDUAL, GROUP (2–4), COLLECTIVE (5 or more), NOMINATION or PRIZE —
     * or {@code null} when nothing tells it. In order: the result (a nomination or a prize); the size of the ensemble; the role (a conductor, director, ballet master, concertmaster or member
     * of a large ensemble leads or plays in a collective; a soloist or composer is an individual project).
     * A chamber musician without the ensemble's size stays undecided: their group could be 2 or 10.
     */
    public static String cnfisKind(Map<String, String> fields) {
        // H145: derived from the facts only — the result, the ensemble's size, the role; never a kind the record picks
        String result = result(fields);
        if (PRIZE.equals(result)) return "PRIZE";
        if (NOMINATION.equals(result)) return "NOMINATION";
        Integer size = parseSize(fields.get(FIELD_ENSEMBLE_SIZE));
        if (size != null && size >= 1) {
            if (size == 1) return "INDIVIDUAL";
            return size <= 4 ? "GROUP" : "COLLECTIVE";
        }
        String role = lower(fields.get(FIELD_ROLE));
        if (role.startsWith("solist") || role.startsWith("compozitor")) return "INDIVIDUAL";
        if (role.startsWith("dirijor") || role.startsWith("regizor") || role.startsWith("maestru de balet")
                || role.startsWith("concert-maestru") || role.equals(ROLE_LARGE_ENSEMBLE_MEMBER.toLowerCase(Locale.ROOT))) {
            return "COLLECTIVE";
        }
        return null;
    }

    private static Integer parseSize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
