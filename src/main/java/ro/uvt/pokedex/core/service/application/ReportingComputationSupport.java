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

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(ReportingComputationSupport.class);

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
     * H68 slice 1: the single criterion-score computation, shared by every path (individual render/export and the
     * group runner). Callers that hold scores keyed by {@link Indicator}
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
        // H68 slice 3 pre-pass: a percent cap defines the flagged indicator's EFFECTIVE score (the OM text
        // caps the item's points), so the capped value must feed EVERY criterion that references the
        // indicator — most importantly the "Total" criterion, which would otherwise count the raw score.
        // The declaring criterion (the one carrying maxPercentOfTotal) determines the effective value.
        Map<Integer, Double> effectiveScoreOverrides =
                computeEffectiveScoreOverrides(criteria, indicators, scoreLookup);

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
                        double score = effectiveScoreOverrides.containsKey(indicatorIndex)
                                ? effectiveScoreOverrides.get(indicatorIndex)
                                : scoreLookup.applyAsDouble(indicator);
                        criterionScore += weight * score;
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

    /**
     * H68 slice 3 — effective (percent-capped) scores per indicator index, determined by each cap's
     * declaring criterion. An indicator flagged in more than one criterion keeps the first declaration
     * (report-config smell; not a real standards shape).
     */
    private static Map<Integer, Double> computeEffectiveScoreOverrides(
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            java.util.function.ToDoubleFunction<Indicator> scoreLookup) {
        Map<Integer, Double> overrides = new HashMap<>();
        for (AbstractReport.Criterion criterion : criteria) {
            if (criterion.getMaxPercentOfTotal() == null || criterion.getMaxPercentOfTotal().isEmpty()
                    || criterion.getIndicatorIndices() == null) {
                continue;
            }
            Map<Integer, Double> contributions = new HashMap<>();
            for (Integer indicatorIndex : criterion.getIndicatorIndices()) {
                if (indicatorIndex == null || indicatorIndex < 0 || indicatorIndex >= indicators.size()
                        || indicators.get(indicatorIndex) == null) {
                    continue;
                }
                double weight = criterion.getWeights() == null
                        ? 1.0
                        : criterion.getWeights().getOrDefault(indicatorIndex, 1.0);
                contributions.merge(indicatorIndex,
                        weight * scoreLookup.applyAsDouble(indicators.get(indicatorIndex)), Double::sum);
            }
            Map<Integer, Double> effective = applyPercentCaps(criterion, contributions);
            for (Integer idx : criterion.getMaxPercentOfTotal().keySet()) {
                if (idx == null || !contributions.containsKey(idx) || overrides.containsKey(idx)) {
                    continue;
                }
                double weight = criterion.getWeights() == null
                        ? 1.0
                        : criterion.getWeights().getOrDefault(idx, 1.0);
                if (weight != 0.0 && effective.get(idx) < contributions.get(idx)) {
                    // Contribution back to score units so other criteria (with their own weights) compose.
                    overrides.put(idx, effective.get(idx) / weight);
                }
            }
        }
        return overrides;
    }

    /**
     * H68 slice 3 — per-criterion notes for percent-capped indicators ("counts as X" annotations). Keyed
     * by criterion index; only caps that actually bind produce entries, so legacy reports contribute
     * nothing and templates can render conditionally. Shared by the live-attrs path (UserReportFacade)
     * and the run-sourced evaluation page (IndividualReportViewModelAssembler).
     */
    public static Map<Integer, List<String>> buildPercentCapNotes(
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            Map<String, Double> indicatorScoresByIndicatorId) {
        Map<Integer, List<String>> notes = new HashMap<>();
        List<AbstractReport.Criterion> safeCriteria = criteria == null ? List.of() : criteria;
        List<Indicator> safeIndicators = indicators == null ? List.of() : indicators;
        for (int c = 0; c < safeCriteria.size(); c++) {
            AbstractReport.Criterion criterion = safeCriteria.get(c);
            if (criterion.getMaxPercentOfTotal() == null || criterion.getMaxPercentOfTotal().isEmpty()
                    || criterion.getIndicatorIndices() == null) {
                continue;
            }
            Map<Integer, Double> contributions = new HashMap<>();
            for (Integer idx : criterion.getIndicatorIndices()) {
                if (idx == null || idx < 0 || idx >= safeIndicators.size() || safeIndicators.get(idx) == null) {
                    continue;
                }
                double weight = criterion.getWeights() == null ? 1.0 : criterion.getWeights().getOrDefault(idx, 1.0);
                double score = indicatorScoresByIndicatorId.getOrDefault(safeIndicators.get(idx).getId(), 0.0);
                contributions.merge(idx, weight * score, Double::sum);
            }
            Map<Integer, Double> effective = applyPercentCaps(criterion, contributions);
            for (Map.Entry<Integer, Double> entry : criterion.getMaxPercentOfTotal().entrySet()) {
                Integer idx = entry.getKey();
                if (idx == null || !contributions.containsKey(idx)) {
                    continue;
                }
                double raw = contributions.get(idx);
                double capped = effective.getOrDefault(idx, raw);
                if (raw - capped > 0.005) {
                    notes.computeIfAbsent(c, k -> new java.util.ArrayList<>()).add(String.format(java.util.Locale.ROOT,
                            "%s: %.2f \u2192 %.2f (max %.0f%% of the criterion total)",
                            safeIndicators.get(idx).getName(), raw, capped, entry.getValue()));
                }
            }
        }
        return notes;
    }

    /**
     * H68 slice 3 — percent-of-criterion caps (plafon procentual), fixed-point semantics: a flagged
     * indicator's contribution is clamped to {@code p_i · T} where {@code T} is the FINAL criterion total
     * (OM 3019/2025 "maximum 10% din punctajul total al perspectivei"). Solved by water-filling: assume
     * every flagged cap binds, {@code T = (rest + Σ non-binding c_i) / (1 − Σ binding p_i)}, release any
     * cap whose raw contribution already fits, repeat (≤ |flagged| passes). Degenerate configs where the
     * binding percents sum to ≥ 100% fall back to percent-of-rest so the division stays defined.
     *
     * <p>Returns the effective (possibly capped) contribution per indicator index — the criterion total is
     * their sum, and callers that annotate per-indicator "counts as" values read the same map.</p>
     */
    public static Map<Integer, Double> applyPercentCaps(
            AbstractReport.Criterion criterion, Map<Integer, Double> contributions) {
        Map<Integer, Double> percents = criterion.getMaxPercentOfTotal();
        if (percents == null || percents.isEmpty()) {
            return contributions;
        }
        Map<Integer, Double> flaggedFractions = new HashMap<>();
        for (Map.Entry<Integer, Double> e : percents.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0 && contributions.containsKey(e.getKey())) {
                flaggedFractions.put(e.getKey(), e.getValue() / 100.0);
            }
        }
        if (flaggedFractions.isEmpty()) {
            return contributions;
        }
        double rest = contributions.entrySet().stream()
                .filter(e -> !flaggedFractions.containsKey(e.getKey()))
                .mapToDouble(Map.Entry::getValue).sum();

        java.util.Set<Integer> binding = new java.util.HashSet<>(flaggedFractions.keySet());
        double capBase = 0.0; // the T that flagged contributions are limited to p_i · T of
        for (int pass = 0; pass <= flaggedFractions.size(); pass++) {
            double bindingFractionSum = binding.stream().mapToDouble(flaggedFractions::get).sum();
            double nonBindingSum = flaggedFractions.keySet().stream()
                    .filter(k -> !binding.contains(k)).mapToDouble(contributions::get).sum();
            if (1.0 - bindingFractionSum <= 0.0) {
                // Degenerate config (binding percents ≥ 100%): the fixed point diverges, so fall back to
                // percent-of-rest — caps are taken from the unflagged base, 0 when that base is 0.
                capBase = rest + nonBindingSum;
                break;
            }
            capBase = (rest + nonBindingSum) / (1.0 - bindingFractionSum);
            // Release caps that don't actually bind at this total; converged when none release.
            final double t = capBase;
            java.util.List<Integer> released = binding.stream()
                    .filter(k -> contributions.get(k) <= flaggedFractions.get(k) * t).toList();
            if (released.isEmpty()) {
                break;
            }
            released.forEach(binding::remove);
        }
        final double finalCapBase = capBase;
        Map<Integer, Double> effective = new HashMap<>(contributions);
        for (Integer k : flaggedFractions.keySet()) {
            effective.put(k, Math.min(contributions.get(k), flaggedFractions.get(k) * finalCapBase));
        }
        return effective;
    }

    /**
     * Stage 1 position-aware eligibility (FEAA 2026 book cap): per-position EFFECTIVE criterion scores for
     * criteria declaring {@link AbstractReport.ThresholdCapAddition}s —
     * {@code effective(pos) = canonicalScore + Σ min(rawIndicator, percent/100 · threshold(refCriterion, pos))}.
     * Render-time only: the persisted canonical {@code criteriaScores} (run compare, org-unit roll-ups, history)
     * never include these additions. Criteria without additions are absent from the result, so legacy reports
     * contribute nothing. An addition whose indicator already sits in the criterion's {@code indicatorIndices}
     * is skipped (it is already summed — adding it again would double-count); positions without a threshold in
     * the referenced criterion contribute 0 for that addition.
     */
    public static Map<Integer, Map<String, Double>> computePositionEffectiveScores(
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            Map<String, Double> indicatorScoresByIndicatorId,
            Map<Integer, Double> criterionScores) {
        return computePositionEffectiveScores(criteria, indicators, indicatorScoresByIndicatorId,
                criterionScores, Map.of());
    }

    /**
     * S2 overload: also composes per-position indicator totals (indicatorId → position → total, from
     * {@code Poz}-referencing formulas). A criterion's effective score for a position is
     * {@code canonical + Σ w_i·(perPos_i − canonical_i)} over its own indicators, plus the threshold-cap
     * additions — a delta composition, so H68 weights apply while the canonical maxTotal / percent-cap
     * clamps are NOT re-run on the per-position value (no current report combines them with
     * position-dependent formulas).
     */
    public static Map<Integer, Map<String, Double>> computePositionEffectiveScores(
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            Map<String, Double> indicatorScoresByIndicatorId,
            Map<Integer, Double> criterionScores,
            Map<String, Map<String, Double>> indicatorScoresByPositionByIndicatorId) {
        Map<Integer, Map<String, Double>> result = new HashMap<>();
        List<AbstractReport.Criterion> safeCriteria = criteria == null ? List.of() : criteria;
        List<Indicator> safeIndicators = indicators == null ? List.of() : indicators;
        Map<String, Map<String, Double>> perPosition = indicatorScoresByPositionByIndicatorId == null
                ? Map.of() : indicatorScoresByPositionByIndicatorId;
        for (int c = 0; c < safeCriteria.size(); c++) {
            AbstractReport.Criterion criterion = safeCriteria.get(c);
            boolean hasAdditions = criterion.getThresholdCapAdditions() != null
                    && !criterion.getThresholdCapAdditions().isEmpty();
            boolean hasPositionIndicators = !perPosition.isEmpty() && criterion.getIndicatorIndices() != null
                    && criterion.getIndicatorIndices().stream().anyMatch(idx ->
                            idx != null && idx >= 0 && idx < safeIndicators.size()
                                    && safeIndicators.get(idx) != null
                                    && safeIndicators.get(idx).getId() != null
                                    && perPosition.containsKey(safeIndicators.get(idx).getId()));
            if (!hasAdditions && !hasPositionIndicators) {
                continue;
            }
            // Positions come from the criterion's own thresholds — an effective score is only meaningful
            // where there is a threshold to compare it against.
            if (criterion.getThresholds() == null || criterion.getThresholds().isEmpty()) {
                continue;
            }
            double base = criterionScores == null ? 0.0 : criterionScores.getOrDefault(c, 0.0);
            Map<String, Double> byPosition = new HashMap<>();
            for (AbstractReport.Threshold ownThreshold : criterion.getThresholds()) {
                if (ownThreshold.getPosition() == null) {
                    continue;
                }
                String position = ownThreshold.getPosition().name();
                double effective = base;
                if (hasPositionIndicators) {
                    for (Integer idx : criterion.getIndicatorIndices()) {
                        if (idx == null || idx < 0 || idx >= safeIndicators.size()
                                || safeIndicators.get(idx) == null || safeIndicators.get(idx).getId() == null) {
                            continue;
                        }
                        Map<String, Double> indicatorByPosition = perPosition.get(safeIndicators.get(idx).getId());
                        Double positionValue = indicatorByPosition == null ? null : indicatorByPosition.get(position);
                        if (positionValue == null) {
                            continue; // no divergence for this position — canonical already counted
                        }
                        double canonical = indicatorScoresByIndicatorId == null ? 0.0
                                : indicatorScoresByIndicatorId.getOrDefault(safeIndicators.get(idx).getId(), 0.0);
                        double weight = criterion.getWeights() == null
                                ? 1.0 : criterion.getWeights().getOrDefault(idx, 1.0);
                        effective += weight * (positionValue - canonical);
                    }
                }
                if (hasAdditions) {
                    for (AbstractReport.ThresholdCapAddition addition : criterion.getThresholdCapAdditions()) {
                        effective += additionValue(addition, c, criterion, safeCriteria, safeIndicators,
                                indicatorScoresByIndicatorId, position);
                    }
                }
                byPosition.put(position, effective);
            }
            if (!byPosition.isEmpty()) {
                result.put(c, byPosition);
            }
        }
        return result;
    }

    /** One addition's capped contribution for one position; 0 when misconfigured or no threshold exists. */
    private static double additionValue(
            AbstractReport.ThresholdCapAddition addition,
            int declaringIndex,
            AbstractReport.Criterion declaringCriterion,
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            Map<String, Double> indicatorScoresByIndicatorId,
            String position) {
        Integer idx = addition.getIndicatorIndex();
        if (idx == null || idx < 0 || idx >= indicators.size() || indicators.get(idx) == null
                || addition.getPercent() == null || addition.getPercent() <= 0) {
            return 0.0;
        }
        if (declaringCriterion.getIndicatorIndices() != null && declaringCriterion.getIndicatorIndices().contains(idx)) {
            logger.warn("thresholdCapAdditions indicator index {} is already summed into criterion {} — skipping "
                    + "the addition to avoid double-counting", idx, declaringIndex);
            return 0.0;
        }
        Integer refIndex = addition.getThresholdCriterionIndex() == null
                ? declaringIndex : addition.getThresholdCriterionIndex();
        if (refIndex < 0 || refIndex >= criteria.size() || criteria.get(refIndex) == null) {
            return 0.0;
        }
        Double thresholdValue = null;
        if (criteria.get(refIndex).getThresholds() != null) {
            for (AbstractReport.Threshold t : criteria.get(refIndex).getThresholds()) {
                if (t.getPosition() != null && t.getPosition().name().equals(position) && t.getValue() != null) {
                    thresholdValue = t.getValue();
                    break;
                }
            }
        }
        if (thresholdValue == null) {
            return 0.0;
        }
        String indicatorId = indicators.get(idx).getId();
        double raw = indicatorId == null || indicatorScoresByIndicatorId == null
                ? 0.0 : indicatorScoresByIndicatorId.getOrDefault(indicatorId, 0.0);
        return Math.min(raw, addition.getPercent() / 100.0 * thresholdValue);
    }

    /**
     * Per-criterion, per-position "counts as" notes for threshold-cap additions, mirroring
     * {@link #buildPercentCapNotes}. Only additions that actually alter the criterion score produce entries
     * (raw > 0), so legacy reports and empty indicators render nothing.
     */
    public static Map<Integer, Map<String, List<String>>> buildThresholdCapNotes(
            List<AbstractReport.Criterion> criteria,
            List<Indicator> indicators,
            Map<String, Double> indicatorScoresByIndicatorId) {
        Map<Integer, Map<String, List<String>>> notes = new HashMap<>();
        List<AbstractReport.Criterion> safeCriteria = criteria == null ? List.of() : criteria;
        List<Indicator> safeIndicators = indicators == null ? List.of() : indicators;
        for (int c = 0; c < safeCriteria.size(); c++) {
            AbstractReport.Criterion criterion = safeCriteria.get(c);
            if (criterion.getThresholdCapAdditions() == null || criterion.getThresholdCapAdditions().isEmpty()
                    || criterion.getThresholds() == null) {
                continue;
            }
            for (AbstractReport.Threshold ownThreshold : criterion.getThresholds()) {
                if (ownThreshold.getPosition() == null) {
                    continue;
                }
                String position = ownThreshold.getPosition().name();
                for (AbstractReport.ThresholdCapAddition addition : criterion.getThresholdCapAdditions()) {
                    Integer idx = addition.getIndicatorIndex();
                    if (idx == null || idx < 0 || idx >= safeIndicators.size() || safeIndicators.get(idx) == null) {
                        continue;
                    }
                    String indicatorId = safeIndicators.get(idx).getId();
                    double raw = indicatorId == null || indicatorScoresByIndicatorId == null
                            ? 0.0 : indicatorScoresByIndicatorId.getOrDefault(indicatorId, 0.0);
                    if (raw <= 0.0) {
                        continue;
                    }
                    double counted = additionValue(addition, c, criterion, safeCriteria, safeIndicators,
                            indicatorScoresByIndicatorId, position);
                    notes.computeIfAbsent(c, k -> new HashMap<>())
                            .computeIfAbsent(position, k -> new java.util.ArrayList<>())
                            .add(String.format(java.util.Locale.ROOT, "%s: %.2f → +%.2f",
                                    safeIndicators.get(idx).getName(), raw, counted));
                }
            }
        }
        return notes;
    }

    /**
     * H95 perspective verdicts: for each perspective, the per-position DA/NU derived from its composition
     * tree over criteria met-ness. A position appears in a perspective's map only when the perspective is
     * APPLICABLE there (at least one leaf has a threshold for it). Leaf truth uses the position-effective
     * criterion score when one exists (Stage 1 / S2), else the canonical score. Malformed nodes
     * (out-of-range refs, non-earlier perspective refs, empty/ambiguous nodes) make the whole perspective
     * inapplicable and are logged — render-time safety over hard failure.
     */
    public static Map<Integer, Map<String, Boolean>> computePerspectiveVerdicts(
            List<AbstractReport.Perspective> perspectives,
            List<AbstractReport.Criterion> criteria,
            Map<Integer, Double> criterionScores,
            Map<Integer, Map<String, Double>> positionEffectiveScores) {
        Map<Integer, Map<String, Boolean>> verdicts = new HashMap<>();
        if (perspectives == null || perspectives.isEmpty()) {
            return verdicts;
        }
        List<AbstractReport.Criterion> safeCriteria = criteria == null ? List.of() : criteria;
        Set<String> positions = thresholdPositions(safeCriteria);
        for (int p = 0; p < perspectives.size(); p++) {
            AbstractReport.Perspective perspective = perspectives.get(p);
            if (perspective == null || perspective.getComposition() == null) {
                continue;
            }
            Map<String, Boolean> byPosition = new HashMap<>();
            for (String position : positions) {
                NodeResult result = evaluateNode(perspective.getComposition(), p, position,
                        safeCriteria, criterionScores, positionEffectiveScores, verdicts);
                if (result == null) { // malformed tree — drop the whole perspective
                    byPosition = null;
                    break;
                }
                if (result.applicable()) {
                    byPosition.put(position, result.met());
                }
            }
            if (byPosition != null && !byPosition.isEmpty()) {
                verdicts.put(p, byPosition);
            }
        }
        return verdicts;
    }

    private static Set<String> thresholdPositions(List<AbstractReport.Criterion> criteria) {
        Set<String> positions = new java.util.LinkedHashSet<>();
        for (AbstractReport.Criterion criterion : criteria) {
            if (criterion.getThresholds() == null) continue;
            for (AbstractReport.Threshold t : criterion.getThresholds()) {
                if (t.getPosition() != null && t.getValue() != null) {
                    positions.add(t.getPosition().name());
                }
            }
        }
        return positions;
    }

    /** One alternative route of an any-rooted perspective — label may be null (client falls back). */
    public record PerspectiveRoute(String label, List<Integer> members, Map<String, Boolean> verdictByPosition) {}

    /**
     * H95 routes: per-child verdicts rendered as a legend under the group header, in two shapes.
     * (1) {@code any} roots with two or more children — the FEAA point-4 "ruta a/b/c/d" alternatives.
     * Routes may SHARE criteria (FEAA's "director grants ≥2" sits in routes b and d), which is why
     * the UI renders them as a legend over the flat tiles rather than as sub-groups. (2) {@code all}
     * roots' EARLIER-PERSPECTIVE refs — the FV Info "Total — verdict" shape, whose group otherwise
     * shows only its sum tile while the B/C/D components fail invisibly ("Total DA" inside a NU group
     * read as a contradiction). Criterion children of {@code all} roots are NOT emitted — they already
     * render as tiles in the group. Verdicts reuse {@link #evaluateNode}'s per-position vacuity rules,
     * so legend chips can never disagree with the group verdict. Empty for malformed trees.
     */
    public static Map<Integer, List<PerspectiveRoute>> computePerspectiveRoutes(
            List<AbstractReport.Perspective> perspectives,
            List<AbstractReport.Criterion> criteria,
            Map<Integer, Double> criterionScores,
            Map<Integer, Map<String, Double>> positionEffectiveScores) {
        Map<Integer, List<PerspectiveRoute>> routes = new HashMap<>();
        if (perspectives == null || perspectives.isEmpty()) {
            return routes;
        }
        List<AbstractReport.Criterion> safeCriteria = criteria == null ? List.of() : criteria;
        Map<Integer, Map<String, Boolean>> verdicts =
                computePerspectiveVerdicts(perspectives, safeCriteria, criterionScores, positionEffectiveScores);
        Set<String> positions = thresholdPositions(safeCriteria);
        for (int p = 0; p < perspectives.size(); p++) {
            AbstractReport.Perspective perspective = perspectives.get(p);
            if (perspective == null || perspective.getComposition() == null || !verdicts.containsKey(p)) {
                continue; // malformed or inapplicable perspectives ship no routes either
            }
            boolean anyRoot = perspective.getComposition().getAny() != null;
            List<AbstractReport.CompositionNode> children = anyRoot
                    ? perspective.getComposition().getAny()
                    : perspective.getComposition().getAll();
            if (children == null || (anyRoot && children.size() < 2)) {
                continue;
            }
            List<PerspectiveRoute> out = new java.util.ArrayList<>();
            for (AbstractReport.CompositionNode child : children) {
                if (!anyRoot && child != null && child.getPerspective() == null) {
                    continue; // all-root criterion children already render as tiles in the group
                }
                Set<Integer> members = new java.util.LinkedHashSet<>();
                collectCriterionRefs(child, members);
                if (child != null && child.getPerspective() != null) {
                    // a perspective-ref row highlights the referenced perspective's own tiles on hover
                    int ref = child.getPerspective();
                    if (ref >= 0 && ref < perspectives.size() && perspectives.get(ref) != null) {
                        collectCriterionRefs(perspectives.get(ref).getComposition(), members);
                    }
                }
                Map<String, Boolean> byPosition = new HashMap<>();
                for (String position : positions) {
                    NodeResult result = evaluateNode(child, p, position, safeCriteria,
                            criterionScores, positionEffectiveScores, verdicts);
                    if (result == null) {
                        out = null; // unreachable in practice (verdict pass already dropped malformed trees)
                        break;
                    }
                    if (result.applicable()) {
                        byPosition.put(position, result.met());
                    }
                }
                if (out == null) {
                    break;
                }
                String label = child.getLabel();
                if ((label == null || label.isBlank()) && child.getPerspective() != null) {
                    int ref = child.getPerspective();
                    if (ref >= 0 && ref < perspectives.size() && perspectives.get(ref) != null
                            && perspectives.get(ref).getName() != null) {
                        label = perspectives.get(ref).getName();
                    }
                }
                out.add(new PerspectiveRoute(label, List.copyOf(members), byPosition));
            }
            if (out != null && !out.isEmpty()) {
                routes.put(p, out);
            }
        }
        return routes;
    }

    /** (applicable, met) for one node at one position; null signals a malformed tree. */
    private record NodeResult(boolean applicable, boolean met) {}

    private static NodeResult evaluateNode(
            AbstractReport.CompositionNode node,
            int perspectiveIndex,
            String position,
            List<AbstractReport.Criterion> criteria,
            Map<Integer, Double> criterionScores,
            Map<Integer, Map<String, Double>> positionEffectiveScores,
            Map<Integer, Map<String, Boolean>> earlierVerdicts) {
        if (node == null) {
            return malformed(perspectiveIndex, "null node");
        }
        int kinds = (node.getAll() != null ? 1 : 0) + (node.getAny() != null ? 1 : 0)
                + (node.getCriterion() != null ? 1 : 0) + (node.getPerspective() != null ? 1 : 0);
        if (kinds != 1) {
            return malformed(perspectiveIndex, "node must set exactly one of all/any/criterion/perspective");
        }
        if (node.getCriterion() != null) {
            int idx = node.getCriterion();
            if (idx < 0 || idx >= criteria.size() || criteria.get(idx) == null) {
                return malformed(perspectiveIndex, "criterion index out of range: " + idx);
            }
            AbstractReport.Criterion criterion = criteria.get(idx);
            Double threshold = null;
            if (criterion.getThresholds() != null) {
                for (AbstractReport.Threshold t : criterion.getThresholds()) {
                    if (t.getPosition() != null && position.equals(t.getPosition().name()) && t.getValue() != null) {
                        threshold = t.getValue();
                        break;
                    }
                }
            }
            if (threshold == null) {
                return new NodeResult(false, false); // inapplicable; all/any decide the vacuous truth value
            }
            double score = positionEffectiveScores != null
                    && positionEffectiveScores.containsKey(idx)
                    && positionEffectiveScores.get(idx).containsKey(position)
                    ? positionEffectiveScores.get(idx).get(position)
                    : (criterionScores == null ? 0.0 : criterionScores.getOrDefault(idx, 0.0));
            return new NodeResult(true, score >= threshold);
        }
        if (node.getPerspective() != null) {
            int ref = node.getPerspective();
            if (ref < 0 || ref >= perspectiveIndex) {
                return malformed(perspectiveIndex, "perspective ref must point to an EARLIER perspective: " + ref);
            }
            Map<String, Boolean> refVerdicts = earlierVerdicts.get(ref);
            if (refVerdicts == null || !refVerdicts.containsKey(position)) {
                return new NodeResult(false, false); // referenced perspective inapplicable here
            }
            return new NodeResult(true, refVerdicts.get(position));
        }
        List<AbstractReport.CompositionNode> children = node.getAll() != null ? node.getAll() : node.getAny();
        if (children.isEmpty()) {
            return malformed(perspectiveIndex, "all/any node with no children");
        }
        boolean isAll = node.getAll() != null;
        boolean anyApplicable = false;
        boolean allMet = true;
        boolean anyMet = false;
        for (AbstractReport.CompositionNode child : children) {
            NodeResult r = evaluateNode(child, perspectiveIndex, position, criteria,
                    criterionScores, positionEffectiveScores, earlierVerdicts);
            if (r == null) {
                return null;
            }
            if (!r.applicable()) {
                continue; // vacuous TRUE in all (doesn't break allMet), vacuous FALSE in any (doesn't set anyMet)
            }
            anyApplicable = true;
            allMet &= r.met();
            anyMet |= r.met();
        }
        if (!anyApplicable) {
            return new NodeResult(false, false);
        }
        return new NodeResult(true, isAll ? allMet : anyMet);
    }

    private static NodeResult malformed(int perspectiveIndex, String reason) {
        logger.warn("H95: malformed composition on perspective {} — {}; perspective disabled", perspectiveIndex, reason);
        return null;
    }

    /**
     * H95: the criterion indices referenced (transitively, through earlier-perspective refs) by any
     * perspective — the "bundled" set the summary count EXCLUDES (top-level = perspectives + unbundled
     * criteria) and the rail groups under headings. Malformed refs are simply not collected.
     */
    public static Set<Integer> bundledCriterionIndices(List<AbstractReport.Perspective> perspectives) {
        Set<Integer> bundled = new java.util.LinkedHashSet<>();
        if (perspectives == null) {
            return bundled;
        }
        for (AbstractReport.Perspective perspective : perspectives) {
            if (perspective != null) {
                collectCriterionRefs(perspective.getComposition(), bundled);
            }
        }
        return bundled;
    }

    private static void collectCriterionRefs(AbstractReport.CompositionNode node, Set<Integer> out) {
        if (node == null) return;
        if (node.getCriterion() != null) out.add(node.getCriterion());
        if (node.getAll() != null) node.getAll().forEach(child -> collectCriterionRefs(child, out));
        if (node.getAny() != null) node.getAny().forEach(child -> collectCriterionRefs(child, out));
        // perspective refs need no recursion here: the referenced perspective's own criteria are
        // collected when that perspective is visited.
    }

    private static String firstAuthorId(ScholardexPublicationView publication) {
        if (publication == null || publication.getAuthors() == null || publication.getAuthors().isEmpty()) {
            return null;
        }
        return publication.getAuthors().getFirst();
    }
}
