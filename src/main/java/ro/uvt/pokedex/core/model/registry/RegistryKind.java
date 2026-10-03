package ro.uvt.pokedex.core.model.registry;

import ro.uvt.pokedex.core.model.activities.Activity;

import java.util.List;

/**
 * H144 — the registries experts rank once for everyone (H142 slice 3 built the first). A researcher names the entity in
 * the kind's reference field; its level comes from the registry, never from the researcher.
 *
 * <p>Per kind: the reference field that names it, its levels (best first), the level a name waiting for the experts
 * counts at ({@code floor}; null when it counts at none — Comisia 28 makes every conference national until shown
 * international, while a gate the standard sets, like "de prestigiu", needs a decision), its categories, the criteria
 * an expert ticks and the bases an expert gives.</p>
 */
public enum RegistryKind {

    /** Festivals, competitions, seasons and tours (H142 slice 3; the CNFIS list seeded it). */
    ARTISTIC_EVENT(Activity.ReferenceField.EVENT_NAME,
            List.of("INTERNATIONAL_TOP", "INTERNATIONAL", "NATIONAL_TOP", "NATIONAL", "LOCAL"), null,
            List.of("FESTIVAL", "COMPETITION", "SEASON", "TOUR", "OTHER"), List.of(),
            List.of("CNFIS_LIST", "CNFIS_CAPITAL_INSTITUTION", "CNFIS_UNION_PARTNERSHIP", "CNFIS_MINISTRY_FUNDING",
                    "TOP_FESTIVAL_ABROAD", "TOP_INSTITUTION_ABROAD", "TOP_FESTIVAL_ROMANIA", "TOP_INSTITUTION_ROMANIA",
                    "REGIONAL_OR_LOCAL", "OTHER")),

    /** Conferences, congresses, symposia: international by Comisia 28's rule (two of its three criteria), else national. */
    SCIENTIFIC_EVENT(Activity.ReferenceField.CONFERENCE_NAME, List.of("INTERNATIONAL", "NATIONAL"), "NATIONAL",
            List.of("CONFERENCE", "CONGRESS", "SYMPOSIUM", "WORKSHOP", "OTHER"),
            List.of("INTERNATIONAL_ORGANISER", "PROCEEDINGS_LANGUAGE", "SESSIONS_LANGUAGE"),
            List.of("COMISIA_28_CRITERIA", "CORE", "OTHER")),

    /** Associations, academies, federations, institutions, funders, agencies, commissions, media. */
    ORGANIZATION(Activity.ReferenceField.ORGANIZATION_NAME, List.of("INTERNATIONAL", "NATIONAL", "LOCAL"), null,
            List.of("ASSOCIATION", "ACADEMY", "FEDERATION", "INSTITUTION", "FUNDER", "AGENCY", "COMMISSION", "MEDIA", "OTHER"),
            List.of(), List.of("SCOPE", "STATUTE", "PRESTIGE", "OTHER")),

    /** Prizes and distinctions, by their reach and their nature. */
    AWARD(Activity.ReferenceField.AWARD_NAME, List.of("INTERNATIONAL", "NATIONAL"), null,
            List.of("SCIENTIFIC", "DIDACTIC", "COUNTRY_PROMOTION", "ARTISTIC", "STATE", "OTHER"), List.of(),
            List.of("AWARDING_BODY", "COMPETITION_SCOPE", "OTHER"));

    /** The criteria Comisia 28 asks two of for an international conference. */
    public static final int COMISIA_28_CRITERIA_NEEDED = 2;

    private final Activity.ReferenceField referenceField;
    private final List<String> levels;
    private final String floor;
    private final List<String> categories;
    private final List<String> criteria;
    private final List<String> bases;

    RegistryKind(Activity.ReferenceField referenceField, List<String> levels, String floor, List<String> categories,
                 List<String> criteria, List<String> bases) {
        this.referenceField = referenceField;
        this.levels = levels;
        this.floor = floor;
        this.categories = categories;
        this.criteria = criteria;
        this.bases = bases;
    }

    public Activity.ReferenceField referenceField() {
        return referenceField;
    }

    public List<String> levels() {
        return levels;
    }

    /** The level a name waiting for the experts counts at, or null. */
    public String floor() {
        return floor;
    }

    public List<String> categories() {
        return categories;
    }

    public List<String> criteria() {
        return criteria;
    }

    public List<String> bases() {
        return bases;
    }

    /** The kind whose entities a reference field names, if any. */
    public static java.util.Optional<RegistryKind> of(Activity.ReferenceField field) {
        for (RegistryKind kind : values()) {
            if (kind.referenceField == field) {
                return java.util.Optional.of(kind);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * The level the scoring reads — international, national or local — for one of this kind's levels: a top
     * international artistic event is international, a national one with top visibility national.
     */
    public static String scoringLevel(String level) {
        if (level == null) return null;
        return switch (level) {
            case "INTERNATIONAL_TOP", "INTERNATIONAL" -> "INTERNATIONAL";
            case "NATIONAL_TOP", "NATIONAL" -> "NATIONAL";
            case "LOCAL" -> "LOCAL";
            default -> null;
        };
    }
}
