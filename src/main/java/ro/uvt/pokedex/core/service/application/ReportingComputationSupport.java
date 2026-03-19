package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.*;
import java.util.stream.Collectors;

public final class ReportingComputationSupport {

    private ReportingComputationSupport() {}

    /**
     * Filters publications by the author-role constraint encoded in the indicator type
     * (ALL / MAIN_AUTHOR / COAUTHOR), then computes the total author score via the
     * scientific production service.
     */
    public static double calculatePublicationScore(
            Indicator indicator,
            List<Author> authors,
            List<Publication> publications,
            ScientificProductionService scientificProductionService) {

        List<Publication> filtered = publications;
        if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR)) {
            filtered = publications.stream()
                    .filter(p -> authors.stream().anyMatch(a -> a.getId().equals(p.getAuthors().get(0))))
                    .collect(Collectors.toList());
        } else if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_COAUTHOR)) {
            filtered = publications.stream()
                    .filter(p -> authors.stream().noneMatch(a -> a.getId().equals(p.getAuthors().get(0))))
                    .collect(Collectors.toList());
        }
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(filtered, indicator);
        return scores.get("total").getAuthorScore();
    }

    /**
     * Applies the TOP_10 selector to a nested score map (publicationTitle → scoringKey → Score).
     * Entries that fall outside the top 10 by author score are removed and totals are rebuilt.
     * Uses an index map for O(1) deduplication within the top-10 list.
     */
    public static void applyFinalSelector(
            Indicator indicator,
            Map<String, Map<String, Score>> scores) {

        if (indicator.getSelector() == null
                || !indicator.getSelector().equals(Indicator.Selector.TOP_10)) {
            return;
        }

        Map<String, Score> topScores = new HashMap<>();
        scores.forEach((k, v) -> topScores.putAll(v));

        List<String> top10 = topScores.entrySet().stream()
                .filter(x -> !x.getKey().equals("total"))
                .sorted(Map.Entry.<String, Score>comparingByValue(
                        Comparator.comparingDouble(Score::getAuthorScore)).reversed())
                .limit(10)
                .map(Map.Entry::getKey)
                .toList();

        Map<String, Integer> top10IndexByTitle = new HashMap<>();
        for (int i = 0; i < top10.size(); i++) {
            top10IndexByTitle.putIfAbsent(top10.get(i), i);
        }
        boolean[] used = new boolean[top10.size()];

        for (String key : scores.keySet()) {
            Iterator<String> it = scores.get(key).keySet().iterator();
            while (it.hasNext()) {
                String title = it.next();
                if (title.equals("total")) {
                    continue;
                }
                Integer idx = top10IndexByTitle.get(title);
                if (idx == null || used[idx]) {
                    it.remove();
                }
                if (idx != null) {
                    used[idx] = true;
                }
            }
            double totalA = 0.0;
            double totalF = 0.0;
            scores.get(key).remove("total");
            for (Score s : scores.get(key).values()) {
                totalA += s.getAuthorScore();
                totalF += s.getScore();
            }
            Score total = new Score();
            total.setScore(totalF);
            total.setAuthorScore(totalA);
            scores.get(key).put("total", total);
        }
    }
}
