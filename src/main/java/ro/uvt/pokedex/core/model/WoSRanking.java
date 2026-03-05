package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.*;

@Data
@Document(collection = "wos.rankings")
public class WoSRanking {
    @Id
    private String id; // Unique composite ID
    @Indexed
    private String name;
    @Indexed
    private String issn;
    @Indexed
    private String eIssn;
    @Indexed
    private List<String> alternativeIssns = new ArrayList<>();
    private Score score;
    private Map<String, Rank> webOfScienceCategoryIndex = new HashMap<>();

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WoSRanking ranking)) return false;
        return Objects.equals(id, ranking.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }



    public String toString(){
        return "WoSRanking{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", issn='" + issn + '\'' +
                ", eIssn='" + eIssn + '\'' +
                ", webOfScienceCategoryIndex=" + webOfScienceCategoryIndex +
                '}';
    }
    @Data
    public static class Rank {
        private Map<Integer, Quarter> qAis = new HashMap<>();
        private Map<Integer, Quarter> qIF = new HashMap<>();
        private Map<Integer, Quarter> qRis = new HashMap<>();
        private Map<Integer, Integer> rankAis = new HashMap<>();
        private Map<Integer, Integer> rankIF = new HashMap<>();
        private Map<Integer, Integer> rankRis = new HashMap<>();
    }

    @Data
    public static class Score {
        private Map<Integer, Double> ais = new HashMap<>();
        private Map<Integer, Double> ris = new HashMap<>();
        private Map<Integer, Double> IF = new HashMap<>();
    }

    public enum Quarter {
        Q1, Q2, Q3, Q4, REMOVED, NOT_FOUND, SCOPUS, LNCS, SENSE, CORE
    }

    public void generateId() {
        this.id = getGeneratedId(this.issn, this.eIssn);
    }

    public void merge(WoSRanking toMerge) {
        List<String> alternatives = new ArrayList<>();
        if(issn == null || issn.isEmpty() || issn.equals("N/A")) {
            if(toMerge.getIssn() != null && !toMerge.getIssn().isEmpty() && !toMerge.getIssn().equals("N/A")) {
                issn = toMerge.getIssn();
            }
        }else{
            if(toMerge.getIssn() != null && !toMerge.getIssn().isEmpty() && !toMerge.getIssn().equals("N/A") && !toMerge.getIssn().equals(issn) && !toMerge.getIssn().equals(eIssn)) {
                alternatives.add(toMerge.getIssn());
            }
        }

        if(eIssn == null || eIssn.isEmpty() || eIssn.equals("N/A")) {
            if(toMerge.getEIssn() != null && !toMerge.getEIssn().isEmpty() && !toMerge.getEIssn().equals("N/A")) {
                eIssn = toMerge.getEIssn();
            }
        }else{
            if(toMerge.getEIssn() != null && !toMerge.getEIssn().isEmpty() && !toMerge.getEIssn().equals("N/A") && !toMerge.getEIssn().equals(eIssn) && !toMerge.getEIssn().equals(issn)) {
                alternatives.add(toMerge.getEIssn());
            }
        }
        this.alternativeIssns = alternatives;
        Score toMergeScore = toMerge.getScore();
        for(Map.Entry<Integer, Double> aisEntry : toMergeScore.getAis().entrySet()) {
            score.getAis().put(aisEntry.getKey(), aisEntry.getValue());
        }
        for(Map.Entry<Integer, Double> risEntry : toMergeScore.getRis().entrySet()) {
            score.getRis().put(risEntry.getKey(), risEntry.getValue());
        }
        for(Map.Entry<Integer, Double> IFEntry : toMergeScore.getIF().entrySet()) {
            score.getIF().put(IFEntry.getKey(), IFEntry.getValue());
        }
        for(Map.Entry<String, Rank> entry : toMerge.getWebOfScienceCategoryIndex().entrySet()) {

            if(webOfScienceCategoryIndex.containsKey(entry.getKey())) {
                Rank rank = webOfScienceCategoryIndex.get(entry.getKey());
                Rank toMergeRank = entry.getValue();
                for(Map.Entry<Integer, Quarter> qAisEntry : toMergeRank.getQAis().entrySet()) {
                    rank.getQAis().put(qAisEntry.getKey(), qAisEntry.getValue());
                }
                for(Map.Entry<Integer, Quarter> qIFEntry : toMergeRank.getQIF().entrySet()) {
                    rank.getQIF().put(qIFEntry.getKey(), qIFEntry.getValue());
                }
                for(Map.Entry<Integer, Quarter> qRisEntry : toMergeRank.getQRis().entrySet()) {
                    rank.getQRis().put(qRisEntry.getKey(), qRisEntry.getValue());
                }
                for(Map.Entry<Integer, Integer> rankAisEntry : toMergeRank.getRankAis().entrySet()) {
                    rank.getRankAis().put(rankAisEntry.getKey(), rankAisEntry.getValue());
                }
                for(Map.Entry<Integer, Integer> rankIFEntry : toMergeRank.getRankIF().entrySet()) {
                    rank.getRankIF().put(rankIFEntry.getKey(), rankIFEntry.getValue());
                }
                for(Map.Entry<Integer, Integer> rankRisEntry : toMergeRank.getRankRis().entrySet()) {
                    rank.getRankRis().put(rankRisEntry.getKey(), rankRisEntry.getValue());
                }
            } else {
                webOfScienceCategoryIndex.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public static String getGeneratedId(String issn, String eIssn) {
        return !issn.equals("") && !issn.equals("N/A") ? issn : !eIssn.equals("") && !eIssn.equals("N/A") ? eIssn : null;
    }

    public static void rankByAisWithQuarterKnown(List<WoSRanking> rankings) {
        Map<String, Map<Integer, List<WoSRanking>>> categoryYearRankings = collectRankingsByCategoryAndYear(rankings);
        rankRankingsByAisForEachYearAndQuarter(categoryYearRankings);
        updateRankingsWithNewRankAisValues(rankings, categoryYearRankings);
    }

    private static Map<String, Map<Integer, List<WoSRanking>>> collectRankingsByCategoryAndYear(List<WoSRanking> rankings) {
        Map<String, Map<Integer, List<WoSRanking>>> categoryYearRankings = new HashMap<>();

        for (WoSRanking ranking : rankings) {
            for (Map.Entry<String, WoSRanking.Rank> entry : ranking.getWebOfScienceCategoryIndex().entrySet()) {
                String category = entry.getKey();
                WoSRanking.Rank rank = entry.getValue();

                for (Map.Entry<Integer, WoSRanking.Quarter> qEntry : rank.getQAis().entrySet()) {
                    int year = qEntry.getKey();

                    categoryYearRankings
                            .computeIfAbsent(category, k -> new HashMap<>())
                            .computeIfAbsent(year, k -> new ArrayList<>())
                            .add(ranking);
                }
            }
        }

        return categoryYearRankings;
    }


    private static Map<String, Map<Integer, List<WoSRanking>>> collectRankingsByCategoryAndYearForUnknown(List<WoSRanking> rankings) {
        Map<String, Map<Integer, List<WoSRanking>>> categoryYearRankings = new HashMap<>();

        for (WoSRanking ranking : rankings) {
            for (Map.Entry<String, WoSRanking.Rank> entry : ranking.getWebOfScienceCategoryIndex().entrySet()) {
                String category = entry.getKey();
                WoSRanking.Rank rank = entry.getValue();

                for(Integer year : ranking.getScore().ais.keySet()){
                    if(rank.getQAis().containsKey(year))
                        continue;
                    categoryYearRankings
                            .computeIfAbsent(category, k -> new HashMap<>())
                            .computeIfAbsent(year, k -> new ArrayList<>())
                            .add(ranking);
                }



            }
        }

        return categoryYearRankings;
    }

    private static void rankRankingsByAisForEachYearAndQuarter(Map<String, Map<Integer, List<WoSRanking>>> categoryYearRankings) {
        for (Map.Entry<String, Map<Integer, List<WoSRanking>>> categoryEntry : categoryYearRankings.entrySet()) {
            String category = categoryEntry.getKey();
            for (Map.Entry<Integer, List<WoSRanking>> yearEntry : categoryEntry.getValue().entrySet()) {
                int year = yearEntry.getKey();
                List<WoSRanking> rankings = yearEntry.getValue();

                Map<WoSRanking.Quarter, List<WoSRanking>> quarterRankings = groupRankingsByQuarter(rankings, year, category);
                rankRankingsWithinEachQuarter(quarterRankings, year, category);
            }
        }
    }

    private static Map<WoSRanking.Quarter, List<WoSRanking>> groupRankingsByQuarter(List<WoSRanking> rankings, int year, String category) {
        Map<WoSRanking.Quarter, List<WoSRanking>> quarterRankings = new HashMap<>();

        for (WoSRanking ranking : rankings) {
            WoSRanking.Quarter quarter = ranking.getWebOfScienceCategoryIndex().get(category).getQAis().get(year);
            if (quarter != null) {
                quarterRankings
                        .computeIfAbsent(quarter, k -> new ArrayList<>())
                        .add(ranking);
            }
        }

        return quarterRankings;
    }

    private static void rankRankingsWithinEachQuarter(Map<WoSRanking.Quarter, List<WoSRanking>> quarterRankings, int year, String category) {
        for (Map.Entry<WoSRanking.Quarter, List<WoSRanking>> quarterEntry : quarterRankings.entrySet()) {
            List<WoSRanking> quarterRankingList = quarterEntry.getValue();
            quarterRankingList.sort((r1, r2) -> Double.compare(
                    r2.getScore().getAis().get(year) != null ? r2.getScore().getAis().get(year) : 0.0,
                    r1.getScore().getAis().get(year) != null ? r1.getScore().getAis().get(year) : 0.0
            ));

            for (int i = 0; i < quarterRankingList.size(); i++) {
                quarterRankingList.get(i).getWebOfScienceCategoryIndex().get(category).getRankAis().put(year, i + 1);
            }
        }
    }

    private static void updateRankingsWithNewRankAisValues(List<WoSRanking> rankings, Map<String, Map<Integer, List<WoSRanking>>> categoryYearRankings) {
        for (WoSRanking ranking : rankings) {
            for (Map.Entry<String, WoSRanking.Rank> entry : ranking.getWebOfScienceCategoryIndex().entrySet()) {
                String category = entry.getKey();
                WoSRanking.Rank rank = entry.getValue();

                for (Map.Entry<Integer, WoSRanking.Quarter> qEntry : rank.getQAis().entrySet()) {
                    int year = qEntry.getKey();

                    if (categoryYearRankings.containsKey(category) &&
                            categoryYearRankings.get(category).containsKey(year)) {
                        categoryYearRankings.get(category).get(year).stream()
                                .filter(r -> r.equals(ranking))
                                .findFirst().ifPresent(updatedRanking -> rank.setRankAis(updatedRanking.getWebOfScienceCategoryIndex().get(category).getRankAis()));
                    }
                }
            }
        }
    }

    public static void rankByAisAndEstablishQuarters(List<WoSRanking> all) {
        Map<String, Map<Integer, List<WoSRanking>>> categoryYearRankings = collectRankingsByCategoryAndYearForUnknown(all);
        establishQuarters(categoryYearRankings);
        updateRankingsWithNewRankAisValues(all, categoryYearRankings);
    }

    private static void establishQuarters(Map<String, Map<Integer, List<WoSRanking>>> categoryYearScores) {
        for (Map.Entry<String, Map<Integer, List<WoSRanking>>> categoryEntry : categoryYearScores.entrySet()) {
            for (Map.Entry<Integer, List<WoSRanking>> yearEntry : categoryEntry.getValue().entrySet()) {
                int year = yearEntry.getKey();
                List<WoSRanking> scores = yearEntry.getValue();
                scores.sort(Comparator.comparingDouble(r ->
                {
                    Score score1 = r.getScore();
                    Map<Integer, Double> ais = score1.ais;
                    return ais.get(year) != null ? ais.get(year) : 0.0;
                }));
                scores = scores.reversed();
                int size = scores.size();
                int quarterSize = (int) Math.ceil(size / 4.0);

                int offset = 0;
                for (int i = 0; i < size; i++) {
                    Quarter quarter;
                    if (i < quarterSize) {
                        quarter = Quarter.Q1;
                    } else if (i < 2 * quarterSize) {
                        offset = quarterSize;
                        quarter = Quarter.Q2;
                    } else if (i < 3 * quarterSize) {
                        offset = 2 * quarterSize;
                        quarter = Quarter.Q3;
                    } else {
                        offset = 3 * quarterSize;
                        quarter = Quarter.Q4;
                    }
                    scores.get(i).getWebOfScienceCategoryIndex().get(categoryEntry.getKey()).getQAis().put(yearEntry.getKey(), quarter);
                    scores.get(i).getWebOfScienceCategoryIndex().get(categoryEntry.getKey()).getRankAis().put(yearEntry.getKey(), (i + 1) - offset);
                }
            }
        }
    }
}
