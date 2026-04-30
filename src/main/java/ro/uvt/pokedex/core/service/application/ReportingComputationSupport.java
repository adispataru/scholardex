package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
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
            List<ScholardexAuthorView> authors,
            List<ScholardexPublicationView> publications,
            ScientificProductionService scientificProductionService) {

        List<ScholardexPublicationView> filtered = switch (indicator.getOutputType()) {
            case PUBLICATIONS_MAIN_AUTHOR -> publications.stream()
                    .filter(p -> {
                        String firstAuthorId = firstAuthorId(p);
                        return firstAuthorId != null && authors.stream().anyMatch(a -> a.getId().equals(firstAuthorId));
                    })
                    .collect(Collectors.toList());
            case PUBLICATIONS_COAUTHOR -> publications.stream()
                    .filter(p -> {
                        String firstAuthorId = firstAuthorId(p);
                        return firstAuthorId == null || authors.stream().noneMatch(a -> a.getId().equals(firstAuthorId));
                    })
                    .collect(Collectors.toList());
            default -> publications;
        };
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(
                filtered.stream().map(ScholardexPublicationView::toScoringPublication).toList(),
                indicator
        );
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

        if (indicator.getSelector() != Indicator.Selector.TOP_10) {
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

        for (Map<String, Score> scoreMap : scores.values()) {
            Iterator<String> it = scoreMap.keySet().iterator();
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
            scoreMap.remove("total");
            for (Score s : scoreMap.values()) {
                totalA += s.getAuthorScore();
                totalF += s.getScore();
            }
            Score total = new Score();
            total.setScore(totalF);
            total.setAuthorScore(totalA);
            scoreMap.put("total", total);
        }
    }

    public static boolean isActivityIndicator(Indicator indicator) {
        return indicator != null
                && indicator.getOutputType() != null
                && indicator.getOutputType().toString().contains("ACTIVIT");
    }

    public static boolean isPublicationIndicator(Indicator indicator) {
        return indicator != null
                && indicator.getOutputType() != null
                && indicator.getOutputType().toString().contains("PUBLICATIONS");
    }

    public static boolean isCitationIndicator(Indicator indicator) {
        if (indicator == null || indicator.getOutputType() == null) {
            return false;
        }
        return indicator.getOutputType().equals(Indicator.Type.CITATIONS)
                || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);
    }

    public static Map<Integer, Double> computeCriterionScores(
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            Map<String, Double> indicatorScoresByIndicatorId) {
        Map<Integer, Double> criterionScores = new HashMap<>();
        for (int i = 0; i < criteria.size(); i++) {
            AbstractReport.Criterion criterion = criteria.get(i);
            double criterionScore = 0.0;
            if (criterion.getIndicatorIndices() == null) {
                criterionScores.put(i, criterionScore);
                continue;
            }
            for (Integer indicatorIndex : criterion.getIndicatorIndices()) {
                if (indicatorIndex == null || indicatorIndex < 0 || indicatorIndex >= indicators.size()) {
                    continue;
                }
                Indicator indicator = indicators.get(indicatorIndex);
                if (indicator != null && indicator.getId() != null) {
                    criterionScore += indicatorScoresByIndicatorId.getOrDefault(indicator.getId(), 0.0);
                }
            }
            criterionScores.put(i, criterionScore);
        }
        return criterionScores;
    }

    private static String firstAuthorId(ScholardexPublicationView publication) {
        if (publication == null || publication.getAuthors() == null || publication.getAuthors().isEmpty()) {
            return null;
        }
        return publication.getAuthors().getFirst();
    }
}
