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
        NON_RANK
    }
}
