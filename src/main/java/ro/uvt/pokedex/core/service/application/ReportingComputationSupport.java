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
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(
                filterByAuthorRole(indicator, authors, publications).stream()
                        .map(ScholardexPublicationView::toScoringPublication).toList(),
                indicator
        );
        return scores.get("total").getAuthorScore();
    }

    /** The indicator's typed author-role filter (ALL / MAIN / CO / FIRST_OR_CORRESPONDING), extracted so callers
     *  that memoize the scoring step can filter first and key the cache by the actual publication set. */
    public static List<ScholardexPublicationView> filterByAuthorRole(
            Indicator indicator,
            List<ScholardexAuthorView> authors,
            List<ScholardexPublicationView> publications) {

        // H52 slice 11d.2: typed author-role dispatch.
        ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole role = indicator.publicationAuthorRole();
        List<ScholardexPublicationView> filtered;
        if (role == ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.MAIN) {
            filtered = publications.stream()
                    .filter(p -> {
                        String firstAuthorId = firstAuthorId(p);
                        return firstAuthorId != null && authors.stream().anyMatch(a -> a.getId().equals(firstAuthorId));
                    })
                    .collect(Collectors.toList());
        } else if (role == ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.CO) {
            filtered = publications.stream()
                    .filter(p -> {
                        String firstAuthorId = firstAuthorId(p);
                        return firstAuthorId == null || authors.stream().noneMatch(a -> a.getId().equals(firstAuthorId));
                    })
                    .collect(Collectors.toList());
        } else if (role == ro.uvt.pokedex.core.model.reporting.scoring.AuthorRole.FIRST_OR_CORRESPONDING) {
            // H63: keep a publication if the researcher is its first author OR one of its corresponding authors.
            // Publications with no known corresponding author (correspondingAuthorIds empty) fall back to first-author.
            Set<String> candidateIds = authors.stream()
                    .map(ScholardexAuthorView::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            filtered = publications.stream()
                    .filter(p -> {
                        String firstAuthorId = firstAuthorId(p);
                        boolean isFirst = firstAuthorId != null && candidateIds.contains(firstAuthorId);
                        boolean isCorresponding = p.getCorrespondingAuthorIds() != null
                                && p.getCorrespondingAuthorIds().stream().anyMatch(candidateIds::contains);
                        return isFirst || isCorresponding;
                    })
                    .collect(Collectors.toList());
        } else {
            filtered = publications;
        }
        return filtered;
    }

    /**
     * Applies the TOP_10 selector to a nested score map (publicationTitle → scoringKey → Score).
     * Entries that fall outside the top 10 by author score are removed and totals are rebuilt.
     * Uses an index map for O(1) deduplication within the top-10 list.
     */
    public static void applyFinalSelector(
            Indicator indicator,
            Map<String, Map<String, Score>> scores) {

        // H52 slice 11d.2: typed-selector check via the Indicator helper.
        if (!indicator.isTopNSelector()) {
            return;
        }
        int limit = indicator.topNLimit();

        Map<String, Score> topScores = new HashMap<>();
        scores.forEach((k, v) -> topScores.putAll(v));

        List<String> top10 = topScores.entrySet().stream()
                .filter(x -> !x.getKey().equals("total"))
                .sorted(Map.Entry.<String, Score>comparingByValue(
                        Comparator.comparingDouble(Score::getAuthorScore)).reversed())
                .limit(limit)
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

    // H52 slice 11e: the {@code is*Indicator} thin-wrapper trio was inlined at every
    // call site (callers now use {@code indicator != null && indicator.isXxxOutput()}
    // directly). The wrappers existed only as a slice-11d.2 transition aid; they
    // duplicated the {@link Indicator} typed helpers without adding behavior.

    public static Map<Integer, Double> computeCriterionScores(
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            Map<String, Double> indicatorScoresByIndicatorId) {
        // Id-keyed lookup (render/export path). A null-id indicator maps to 0.0.
        return computeCriterionScores(criteria, indicators,
                indicator -> indicator.getId() == null
                        ? 0.0
                        : indicatorScoresByIndicatorId.getOrDefault(indicator.getId(), 0.0));
    }

    /**
     * H68 slice 1: the single criterion-score computation, shared by every path (individual render/export, group
     * runner, and the group {@code IndividualReportComputer}). Callers that hold scores keyed by {@link Indicator}
     * object (rather than by id — their indicators may have null ids) delegate here so weights + the criterion cap
     * apply consistently — previously the group paths did a plain sum and silently ignored {@code weights}. Invalid
     * indicator indices are reported into {@code errors}.
     */
    public static Map<Integer, Double> computeCriterionScores(
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            Map<Indicator, Double> indicatorScoresByIndicator,
            List<String> errors) {
        if (errors != null && criteria != null) {
            for (int i = 0; i < criteria.size(); i++) {
                List<Integer> idxs = criteria.get(i).getIndicatorIndices();
                if (idxs == null) {
                    continue;
                }
                for (Integer idx : idxs) {
                    if (idx == null || idx < 0 || idx >= indicators.size()) {
                        errors.add("Invalid indicator index " + idx + " in criterion " + i);
                    }
                }
            }
        }
        // Object-keyed lookup (group paths) — works even when indicators carry null ids.
        return computeCriterionScores(criteria == null ? List.of() : criteria, indicators,
                indicator -> indicatorScoresByIndicator == null
                        ? 0.0
                        : indicatorScoresByIndicator.getOrDefault(indicator, 0.0));
    }

    /**
     * The one place criterion scores are aggregated: weighted sum over the criterion's indicators (H65 weights;
     * absent → 1.0) clamped to the optional criterion cap (H68 {@code maxTotal}; null → no cap). The score lookup is
     * supplied by the caller so both the id-keyed and object-keyed paths share this logic.
     */
    private static Map<Integer, Double> computeCriterionScores(
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            java.util.function.ToDoubleFunction<Indicator> scoreLookup) {
        Map<Integer, Double> criterionScores = new HashMap<>();
        for (int i = 0; i < criteria.size(); i++) {
            AbstractReport.Criterion criterion = criteria.get(i);
            double criterionScore = 0.0;
            if (criterion.getIndicatorIndices() != null) {
                for (Integer indicatorIndex : criterion.getIndicatorIndices()) {
                    if (indicatorIndex == null || indicatorIndex < 0 || indicatorIndex >= indicators.size()) {
                        continue;
                    }
                    Indicator indicator = indicators.get(indicatorIndex);
                    if (indicator != null) {
                        double weight = criterion.getWeights() == null
                                ? 1.0
                                : criterion.getWeights().getOrDefault(indicatorIndex, 1.0);
                        criterionScore += weight * scoreLookup.applyAsDouble(indicator);
                    }
                }
            }
            // H68 slice 2: criterion-level cap (plafon) — clamp the aggregated score. Null = no cap.
            if (criterion.getMaxTotal() != null) {
                criterionScore = Math.min(criterionScore, criterion.getMaxTotal());
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
