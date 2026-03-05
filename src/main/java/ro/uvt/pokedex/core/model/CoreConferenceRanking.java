package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

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
        if(yearlyRankings.containsKey(year)){
            return yearlyRankings.get(year);
        }
        YearlyRanking closest = null;
        for (int i = 1; i <= 5; i++) {
            if (yearlyRankings.containsKey(year - i)) {
                closest = yearlyRankings.get(year - i);
                break;
            }
            if(yearlyRankings.containsKey(year + i)) {
                closest = yearlyRankings.get(year + i);
                break;
            }
        }
        return closest;
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
