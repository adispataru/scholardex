package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

@Data
@Document
public class CoreConferenceRanking {
    @Id
    private String id;
    private String source;
    @Indexed
    private String sourceId;
    @Indexed
    private String name;
    @Indexed
    private String acronym;
    private Map<Integer, YearlyRanking> yearlyRankings;

    public void generateId(){
        this.id = String.format("%s-%s", acronym, name);
    }

    public static String getGeneratedId(String acronym, String name){
        return String.format("%s-%s", acronym, name);
    }

    public YearlyRanking getClosestYear(int year) {
        Map<Integer, YearlyRanking> normalizedRankings = normalizeYearlyRankings();
        if (normalizedRankings.containsKey(year)) {
            return normalizedRankings.get(year);
        }
        YearlyRanking closest = null;
        for (int i = 1; i <= 5; i++) {
            if (normalizedRankings.containsKey(year - i)) {
                closest = normalizedRankings.get(year - i);
                break;
            }
            if (normalizedRankings.containsKey(year + i)) {
                closest = normalizedRankings.get(year + i);
                break;
            }
        }
        return closest;
    }

    /** Editions sorted ascending by year, with year keys normalized (Mongo may store them as strings). */
    public Map<Integer, YearlyRanking> sortedYearlyRankings() {
        return normalizeYearlyRankings();
    }

    private Map<Integer, YearlyRanking> normalizeYearlyRankings() {
        if (yearlyRankings == null || yearlyRankings.isEmpty()) {
            return Map.of();
        }
        Map<Integer, YearlyRanking> normalized = new TreeMap<>(Comparator.naturalOrder());
        for (Map.Entry<?, YearlyRanking> entry : ((Map<?, YearlyRanking>) (Map<?, ?>) yearlyRankings).entrySet()) {
            Integer normalizedYear = normalizeYearKey(entry.getKey());
            if (normalizedYear != null && entry.getValue() != null) {
                normalized.put(normalizedYear, entry.getValue());
            }
        }
        return normalized;
    }

    private Integer normalizeYearKey(Object rawKey) {
        if (rawKey instanceof Integer value) {
            return value;
        }
        if (rawKey instanceof Number value) {
            return value.intValue();
        }
        if (rawKey == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(rawKey));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Data
    public static class YearlyRanking {
        private Rank rank;
        private String rankString;
        private String[] fieldsOfResearch;
        private String[] fieldsOfResearchNames;
    }

    public enum Rank {
        A_STAR,
        A,
        B,
        C,
        D,
        Australasian,
        AustralasianA,
        AustralasianB,
        AustralasianC,
        AustralasianD,
        National,
        National_Regional,
        REMOVED,
        NON_RANK;

        /**
         * Numeric tier for trend plotting, higher = better. The Australasian sub-tiers slot just under their
         * global counterparts (a historical scale that predates the global one); National/Regional and
         * REMOVED/NON_RANK sit at the bottom so a drop off the list reads as a decline.
         */
        public int tierValue() {
            return switch (this) {
                case A_STAR -> 12;
                case A -> 11;
                case AustralasianA -> 10;
                case B -> 9;
                case AustralasianB -> 8;
                case C -> 7;
                case AustralasianC -> 6;
                case D -> 5;
                case AustralasianD -> 4;
                case Australasian -> 3;
                case National -> 2;
                case National_Regional -> 1;
                case REMOVED, NON_RANK -> 0;
            };
        }

        /** Human label (A*, Regional, ...) — the single place the display names live. */
        public String displayLabel() {
            return switch (this) {
                case A_STAR -> "A*";
                case AustralasianA -> "Australasian A";
                case AustralasianB -> "Australasian B";
                case AustralasianC -> "Australasian C";
                case AustralasianD -> "Australasian D";
                case National_Regional -> "Regional";
                case NON_RANK -> "Unranked";
                case REMOVED -> "Removed";
                default -> name();
            };
        }
    }
}
