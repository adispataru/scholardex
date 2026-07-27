package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaContext;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScientificProductionService {
    private static final Logger log = LoggerFactory.getLogger(ScientificProductionService.class);

    private final ScoringFactoryService scoringFactoryService;
    private final FormulaEvaluator formulaEvaluator;
    /** H79: DOAJ fee-journal lookup for the {@code feeJournal} formula variable (2026 APC exclusion). The @Primary
     *  {@code ReportingLookupFacade} is injected; default-false for lightweight non-Postgres contexts. */
    private final ReportingLookupPort reportingLookupPort;


    /**
     * H60: the publication-year inclusion filter for an indicator's {@code yearRangeSpec}. Fast no-op for
     * {@link YearRangeSpec.AllYears} (every current indicator) and when a {@code PreviousNYears} window can't resolve
     * (no referenceYear in scope — the legacy unenforced behaviour). Publications with no resolvable year are kept.
     */
    private List<? extends ScoringPublicationReadModel> filterByYearRange(
            List<? extends ScoringPublicationReadModel> publications, Indicator indicator) {
        YearRangeSpec spec = indicator.getEffectiveYearRange();
        if (spec instanceof YearRangeSpec.AllYears) {
            return publications;
        }
        // H60: relative windows resolve against the run's referenceYear, defaulting to the current year on live
        // re-score paths (Absolute ignores it). The central default means every re-score path filters correctly
        // without each having to set the context; the build path overrides with the stored year for replay.
        int ref = ScoringReferenceYearContext.currentOrCurrentYear();
        return publications.stream()
                .filter(pub -> {
                    Optional<Integer> year = PersistenceYearSupport.extractYear(pub.getCoverDate(), pub.getId(), log);
                    return year.isEmpty() || spec.includes(year.get(), ref);
                })
                .toList();
    }

    /** The scores map plus the publications that were dropped from it, keyed by title, with their zero {@link Score}s. */
    public record ScoredProductionResult(Map<String, Score> scores, Map<String, Score> excluded) {}

    /**
     * S2 position-aware scoring: scoringInfo key under which a per-item score (and the synthetic "total"
     * Score) carries a {@code Map<String position, Double>} of values that DIFFER from the canonical
     * authorScore. Absent when the indicator's formula never references {@code Poz} or no position diverges.
     */
    public static final String AUTHOR_SCORE_BY_POSITION = "authorScoreByPosition";

    /** Compiled-once "does this formula reference Poz" check, cached per formula string. */
    private static final Map<String, Boolean> FORMULA_REFERENCES_POZ =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean formulaReferencesPoz(String formula) {
        if (formula == null || formula.isBlank()) {
            return false;
        }
        return FORMULA_REFERENCES_POZ.computeIfAbsent(formula, f ->
                ro.uvt.pokedex.core.service.reporting.formula.FormulaTokenizer.tokenize(f).stream()
                        .anyMatch(t -> t.type() == ro.uvt.pokedex.core.service.reporting.formula.FormulaTokenizer.Type.IDENT
                                && "Poz".equals(t.text())));
    }

    public Map<String, Score> calculateScientificProductionScore(List<? extends ScoringPublicationReadModel> publications, Indicator indicator) {
        return calculateScientificProductionScoreDetailed(publications, indicator).scores();
    }

    public ScoredProductionResult calculateScientificProductionScoreDetailed(
            List<? extends ScoringPublicationReadModel> publications, Indicator indicator) {

        // H60: article-inclusion — drop publications outside the indicator's yearRangeSpec window before scoring/
        // counting. No-op for AllYears (the current default for every indicator), so this is score-neutral until an
        // indicator is configured with PreviousNYears/Absolute. Resolved against the run's thread-scoped referenceYear.
        publications = filterByYearRange(publications, indicator);

        // H52 slice 11d.1: typed-strategy dispatch. The legacy GENERIC_COUNT
        // short-circuit reads the strategy off the {@link IndicatorKind} now;
        // the {@code IndicatorKind.GenericCount} record exists but isn't reachable
        // through {@code fromLegacy}, because the legacy {@code (PUBLICATIONS,
        // GENERIC_COUNT)} pairing maps to {@code Publications(ALL, GENERIC_COUNT)}.
        if(indicator.isGenericCount()) {
            Map<String, Score> result = new HashMap<>();
            publications.forEach(pub -> {
                Score score = new Score();
                score.setScore(1.0);
                score.setAuthorScore(1.0);
                result.put(pub.getTitle(), score);
            });
            Score total = new Score();
            total.setAuthorScore(publications.size());
            result.put("total", total);
            return new ScoredProductionResult(result, Map.of());
        }
        ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());

        double totalScore = 0;
        Map<String, Score> interResult = new HashMap<>();
        // Publications the gate dropped from the scores map, kept with their zero Score (whose
        // scoringInfo.zeroReason says WHY) so detail views can explain instead of hiding them.
        Map<String, Score> excluded = new HashMap<>();
        if(scoringService != null) {
            for (ScoringPublicationReadModel publication : publications) {
                Score score = calculatePublicationScore(publication, indicator, scoringService);
                if(score.getScore() + score.getAuthorScore() > 0.0) {
                    interResult.put(publication.getTitle(), score);
                    totalScore += score.getAuthorScore();
                } else {
                    excluded.put(publication.getTitle(), score);
                }
            }
        }
        Map<String, Score> result = new HashMap<>();
        // H52 slice 11d.3: typed-selector check via the Indicator helpers. The
        // legacy {@code Selector.TOP_10} maps to {@code TopN(10)} so the limit is
        // read from the typed slot instead of hardcoding 10.
        if (indicator.isTopNSelector()) {
            int topLimit = indicator.topNLimit();
            {
                totalScore = 0.0;
                if(publications.size() > topLimit) {
                    publications = new ArrayList<>(publications);
                    publications.sort((p1, p2) -> Double.compare(
                            interResult.get(p2.getTitle()) != null ? interResult.get(p2.getTitle()).getAuthorScore(): 0,
                            interResult.get(p1.getTitle()) != null ? interResult.get(p1.getTitle()).getAuthorScore() : 0));
                }
                int limit = Math.min(topLimit, publications.size());
                for (int i = 0; i < limit; i++) {
                    ScoringPublicationReadModel pub = publications.get(i);
                    Score score = interResult.get(pub.getTitle());
                    if(score != null) {
                        result.put(pub.getTitle(), score);
                        totalScore += score.getAuthorScore();
                    }
                }
                for (Map.Entry<String, Score> entry : interResult.entrySet()) {
                    if (!result.containsKey(entry.getKey())) {
                        entry.getValue().getScoringInfo().put("zeroReason", "NOT_IN_TOP_N");
                        excluded.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        } else if (indicator.isDistinctForumsSelector()) {
            // PD 2026 mentor Q2-diversity: items keep their per-work scores, but the TOTAL is the
            // number of distinct venues among the positive ones — the rule counts journals, not works.
            result = interResult;
            java.util.Set<String> distinctForums = new java.util.HashSet<>();
            for (ScoringPublicationReadModel publication : publications) {
                Score score = interResult.get(publication.getTitle());
                if (score != null && score.getAuthorScore() > 0 && publication.getForumId() != null) {
                    distinctForums.add(publication.getForumId());
                }
            }
            totalScore = distinctForums.size();
        } else if (indicator.isPerForumCapSelector()) {
            // FSP I9/I10: keep at most n positive items per forum (each conference edition = its
            // proceedings forum), taking the highest-scoring; the total sums only the kept items.
            int cap = indicator.perForumCapLimit();
            totalScore = 0.0;
            Map<String, Integer> keptPerForum = new HashMap<>();
            List<ScoringPublicationReadModel> positives = new ArrayList<>();
            for (ScoringPublicationReadModel publication : publications) {
                Score score = interResult.get(publication.getTitle());
                if (score != null && score.getAuthorScore() > 0) {
                    positives.add(publication);
                }
            }
            // Highest author score first so the cap keeps the best contributions per edition.
            positives.sort((p1, p2) -> Double.compare(
                    interResult.get(p2.getTitle()).getAuthorScore(),
                    interResult.get(p1.getTitle()).getAuthorScore()));
            for (ScoringPublicationReadModel publication : positives) {
                Score score = interResult.get(publication.getTitle());
                // A missing forumId can't be grouped into an edition — treat each as its own bucket.
                String forumKey = publication.getForumId() != null
                        ? publication.getForumId()
                        : "__no_forum__" + publication.getTitle();
                int kept = keptPerForum.getOrDefault(forumKey, 0);
                if (kept < cap) {
                    result.put(publication.getTitle(), score);
                    totalScore += score.getAuthorScore();
                    keptPerForum.put(forumKey, kept + 1);
                } else {
                    score.getScoringInfo().put("zeroReason", "OVER_PER_FORUM_CAP");
                    excluded.put(publication.getTitle(), score);
                }
            }
        } else if (indicator.isTopNPerForumYearSelector()) {
            // FEAA 2026 articles: at most perForumYearCap per (journal, publication-year) — Core/Info
            // articles (typed multiplier M >= exemptMultiplierMin) are exempt from the per-journal cap —
            // then the global top-N by author score. Highest-scoring items win both cuts.
            var spec = indicator.topNPerForumYearSelector();
            totalScore = 0.0;
            List<ScoringPublicationReadModel> positives = new ArrayList<>();
            for (ScoringPublicationReadModel publication : publications) {
                Score score = interResult.get(publication.getTitle());
                if (score != null && score.getAuthorScore() > 0) {
                    positives.add(publication);
                }
            }
            positives.sort((p1, p2) -> Double.compare(
                    interResult.get(p2.getTitle()).getAuthorScore(),
                    interResult.get(p1.getTitle()).getAuthorScore()));
            Map<String, Integer> keptPerForumYear = new HashMap<>();
            int keptCount = 0;
            for (ScoringPublicationReadModel publication : positives) {
                Score score = interResult.get(publication.getTitle());
                boolean exempt = score.getMultiplier() != null
                        && score.getMultiplier() >= spec.exemptMultiplierMin();
                String bucket = (publication.getForumId() != null
                        ? publication.getForumId() : "__no_forum__" + publication.getTitle())
                        + "@" + score.getYear();
                if (!exempt && keptPerForumYear.getOrDefault(bucket, 0) >= spec.perForumYearCap()) {
                    score.getScoringInfo().put("zeroReason", "OVER_PER_FORUM_CAP");
                    excluded.put(publication.getTitle(), score);
                    continue;
                }
                if (keptCount >= spec.topN()) {
                    score.getScoringInfo().put("zeroReason", "NOT_IN_TOP_N");
                    excluded.put(publication.getTitle(), score);
                    continue;
                }
                result.put(publication.getTitle(), score);
                totalScore += score.getAuthorScore();
                keptCount++;
                if (!exempt) {
                    keptPerForumYear.merge(bucket, 1, Integer::sum);
                }
            }
            // Parity with the TopN branch: interResult entries that weren't kept (e.g. authorScore == 0
            // gate survivors) are reported through the excluded map, reason intact.
            for (Map.Entry<String, Score> entry : interResult.entrySet()) {
                if (!result.containsKey(entry.getKey()) && !excluded.containsKey(entry.getKey())) {
                    excluded.put(entry.getKey(), entry.getValue());
                }
            }
        }else{
            result = interResult;
        }
        Score total = new Score();
        total.setAuthorScore(totalScore);
        stampTotalsByPosition(indicator, result, total);
        result.put("total", total);

        return new ScoredProductionResult(result, excluded);
    }

    /**
     * S2 position-aware scoring: sums the kept items' per-position values (falling back to each item's
     * canonical authorScore) into the synthetic total Score, storing only positions whose sum differs
     * from the canonical total. The kept set itself is the CANONICAL selection — for {@code TopN} the
     * position variants are summed over the canonical top-N, not a per-position re-selection (documented
     * approximation). {@code DistinctForums} totals count venues, not points, so per-position sums do not
     * apply and nothing is stamped.
     */
    private static void stampTotalsByPosition(Indicator indicator, Map<String, Score> keptScores, Score total) {
        if (indicator.isDistinctForumsSelector()) {
            return;
        }
        List<Map<String, Double>> divergentItems = new ArrayList<>();
        for (Score score : keptScores.values()) {
            Object byPosition = score.getScoringInfo().get(AUTHOR_SCORE_BY_POSITION);
            if (byPosition instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Double> typed = (Map<String, Double>) byPosition;
                divergentItems.add(typed);
            }
        }
        if (divergentItems.isEmpty()) {
            return;
        }
        Map<String, Double> totalsByPosition = new LinkedHashMap<>();
        for (Position position : Position.values()) {
            double sum = 0.0;
            // Canonical reference accumulated over the SAME items in the SAME order — the selector pass
            // sums in a different iteration order, so comparing against total.getAuthorScore() directly
            // produced float-drift false divergences (entries equal to canonical up to rounding).
            double canonicalSum = 0.0;
            for (Score score : keptScores.values()) {
                Object byPosition = score.getScoringInfo().get(AUTHOR_SCORE_BY_POSITION);
                Double positionValue = byPosition instanceof Map<?, ?> map
                        ? (Double) ((Map<?, ?>) map).get(position.name()) : null;
                sum += positionValue != null ? positionValue : score.getAuthorScore();
                canonicalSum += score.getAuthorScore();
            }
            if (sum != canonicalSum) {
                totalsByPosition.put(position.name(), sum);
            }
        }
        if (!totalsByPosition.isEmpty()) {
            total.getScoringInfo().put(AUTHOR_SCORE_BY_POSITION, totalsByPosition);
        }
    }

    public Map<String, Score> calculateScientificImpactScore(
            ScoringPublicationReadModel cited,
            List<? extends ScoringPublicationReadModel> publications,
            Indicator indicator
    ) {
        return calculateScientificImpactScore(cited, publications, indicator, null);
    }

    public Map<String, Score> calculateScientificImpactScore(
            ScoringPublicationReadModel cited,
            List<? extends ScoringPublicationReadModel> publications,
            Indicator indicator,
            Map<String, Score> cachedBaseScoresByCitingPublicationId
    ) {
        long totalStartedAtNanos = System.nanoTime();
        Map<String, Score> result = new HashMap<>();
        // H52 slice 11d.1: same GenericCount short-circuit as the production
        // path; CITATIONS shows up as {@code IndicatorKind.Citations(policy, …)} (H61 self-citation policy).
        if(indicator.isGenericCount()) {
            publications.forEach(pub -> {
                Score score = new Score();
                score.setScore(1.0);
                score.setAuthorScore(1.0);
                result.put(pub.getTitle(), score);
            });
            Score total = new Score();
            total.setAuthorScore(publications.size());
            result.put("total", total);
            return result;
        }
        ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());
        double totalAuthorScore = 0;
        double totalScore = 0;
        long baseScoreLookupNanos = 0L;
        long formulaEvalNanos = 0L;
        long aggregationNanos = 0L;
        int positiveScores = 0;
        if(scoringService != null) {
            for (ScoringPublicationReadModel publication : publications) {
                ScoreComputationTiming scoreTiming = new ScoreComputationTiming();
                Score score = calculateCitationScore(
                        cited,
                        publication,
                        indicator,
                        scoringService,
                        cachedBaseScoresByCitingPublicationId,
                        scoreTiming
                );
                baseScoreLookupNanos += scoreTiming.baseScoreLookupNanos();
                formulaEvalNanos += scoreTiming.formulaEvalNanos();

                long aggregationStartedAtNanos = System.nanoTime();
                if(score.getScore() + score.getAuthorScore() > 0.0) {
                    totalAuthorScore += score.getAuthorScore();
                    totalScore += score.getScore();
                    result.put(publication.getTitle(), score);
                    positiveScores++;
                }
                aggregationNanos += (System.nanoTime() - aggregationStartedAtNanos);
            }
        }
        Score total = new Score();
        total.setAuthorScore(totalAuthorScore);
        total.setScore(totalScore);

        result.put("total", total);
        if (log.isDebugEnabled()) {
            String citedId = cited == null ? "null" : cited.getId();
            log.debug(
                    "Scientific impact timings [citedId={}, kind={}, citingPublications={}, matchedScores={}]: baseScoreLookupMs={}, formulaEvalMs={}, aggregationMs={}, totalMs={}",
                    citedId,
                    indicator.getEffectiveKind(),
                    publications.size(),
                    positiveScores,
                    nanosToMillis(baseScoreLookupNanos),
                    nanosToMillis(formulaEvalNanos),
                    nanosToMillis(aggregationNanos),
                    nanosToMillis(System.nanoTime() - totalStartedAtNanos)
            );
        }
        return result;
    }

    private Score calculatePublicationScore(ScoringPublicationReadModel publication, Indicator indicator, ScoringService scoringService) {
        Score score = getScore(publication, publication, indicator, scoringService, null, null);
        // Display the publication's OWN year, not the scoring/ranking year. Fallback scorers (SCOPUS C/D, LNCS,
        // SENSE) set the score year to a constant (maxAvailableYear / LAST_CORE_YEAR / LAST_SENSE_YEAR), which
        // showed e.g. 2023 for a 2020 paper. The resolved ranking year stays in scoringInfo.resolvedYear.
        ro.uvt.pokedex.core.service.application.PersistenceYearSupport
                .extractYear(publication.getCoverDate(), publication.getId(), log)
                .ifPresent(score::setYear);
        return score;
    }

    private Score calculateCitationScore(ScoringPublicationReadModel cited, ScoringPublicationReadModel citing, Indicator indicator, ScoringService scoringService) {
        return getScore(cited, citing, indicator, scoringService, null, null);
    }

    private Score calculateCitationScore(
            ScoringPublicationReadModel cited,
            ScoringPublicationReadModel citing,
            Indicator indicator,
            ScoringService scoringService,
            Map<String, Score> cachedBaseScoresByCitingPublicationId,
            ScoreComputationTiming timing
    ) {
        return getScore(cited, citing, indicator, scoringService, cachedBaseScoresByCitingPublicationId, timing);
    }

    private Score getScore(
            ScoringPublicationReadModel cited,
            ScoringPublicationReadModel citing,
            Indicator indicator,
            ScoringService scoringService,
            Map<String, Score> cachedBaseScoresByCitingPublicationId,
            ScoreComputationTiming timing
    ) {
        // Universal subtype gate: only original research contributions carry forum points,
        // across every domain/strategy. Non-research subtypes (editorial, erratum, note,
        // letter, …) score nothing whether they are the candidate's own publication
        // (perspective b) or a citing publication (perspective c).
        if (!PublicationSubtypeSupport.isResearchContribution(citing)) {
            Score gated = new Score();
            gated.getScoringInfo().put("zeroReason", "NON_RESEARCH_SUBTYPE");
            return gated;
        }

        Score baseScore = null;
        long baseScoreLookupNanos = 0L;
        if (cachedBaseScoresByCitingPublicationId != null && citing != null && citing.getId() != null) {
            baseScore = cachedBaseScoresByCitingPublicationId.get(citing.getId());
        }
        if (baseScore == null) {
            long baseScoreLookupStartedAtNanos = System.nanoTime();
            baseScore = scoringService.getScore(citing, indicator);
            baseScoreLookupNanos = System.nanoTime() - baseScoreLookupStartedAtNanos;
        }
        if (timing != null) {
            timing.addBaseScoreLookupNanos(baseScoreLookupNanos);
        }
        Score result = copyScore(baseScore);
        // INFO standard, perspective c (2026 Anexa, Comisia 2): a citing publication in a category-D or
        // out-of-list forum confers 1 point — "categoria D: 1 punct" and "pentru citări în ... publicații
        // în forumuri din afara listelor precizate, (S^i)_j va fi 1". The CS base scorers return 0 for
        // unranked journals because perspective b counts only A*..C forums; that zero is correct there but
        // floors to 1 for citations. Gated on isCitationsOutput(): calculatePublicationScore (perspective
        // b) funnels through this same method with cited==citing, and a Publications-kind indicator must
        // NEVER receive the floor. Rule-based exclusions stay excluded ("inclusiv reducerile sau
        // excluderile"): a zero carrying a zeroReason (EXCLUDED_VENUE, VENUE_TYPE_MISMATCH) is not an
        // out-of-list venue. CS strategy only — other domains' standards define no such floor.
        if (result.getScore() == 0
                && indicator.isCitationsOutput()
                && ScoringStrategy.CS.name().equals(indicator.getScoringStrategy())
                && result.getScoringInfo().get("zeroReason") == null) {
            result.setScore(1.0);
            result.setCoreRankingEquivalent("D");
            result.setScoringSource("OUT_OF_LIST_D");
        }
        if(result.getScore() > 0) {
            int numberOfAuthors = cited.getAuthorCount();

            boolean feeJournal = citing != null && reportingLookupPort.isFeeJournal(citing.getForumId());
            boolean topAB = isTopAStarAB(result);
            // H52 slice 11c: typed-only path. M comes from the typed multiplier slot
            // (populated by EconomicsJournalScoringService); the legacy
            // {@code extra["M"]} bag is gone.
            FormulaContext.Builder builder = FormulaContext.builder()
                    .put("S", result.getScore())
                    .put("N", numberOfAuthors)
                    // H65: effective author count (physics Nef) — the divisor for AIS/Nef-style indicators. A 0-author
                    // pub yields Nef=0 → S/Nef is non-finite, caught by the guard below (contributes 0).
                    .put("Nef", EffectiveAuthorCountSupport.computeNef(numberOfAuthors))
                    .put("Q", result.getQuarter())
                    // H79: fee-conditioned (gold-OA APC) journal flag — bound on the SCORED forum (the candidate's pub
                    // in perspective b, or the citing pub in perspective c). Only 2026 formulas gate on it; every
                    // pre-2026 formula ignores it, so existing indicators are unaffected.
                    .put("feeJournal", feeJournal)
                    // H79: category-based eligibility for the 2026 "top A*/A/B" indicators. The point threshold
                    // S>=4 is a proxy for "category in {A*,A,B}" that holds for every item EXCEPT a workshop, whose
                    // 2026 category (id_parA82: A*/A/B parents -> C) diverges from its inherited 6/4 points. Only
                    // 2026 top indicators gate on this; pre-2026 formulas ignore it.
                    .put("topAB", topAB)
                    // PD 2026: resolved (crosswalked) subtype code — "ar"/"re"/"cp"/… — so eligibility formulas
                    // split by WoS document type per indicator (director: article/review only; mentor also accepts
                    // journal proceedings papers). Empty string when unresolvable so string compares stay null-safe.
                    .put("docType", citing != null && PublicationSubtypeSupport.resolveSubtype(citing) != null
                            ? PublicationSubtypeSupport.resolveSubtype(citing) : "")
                    // PD 2026: the scorer's category label ("A*"/"A"/… for CORE conferences) — lets the CORE A/A*
                    // equivalence formula gate on the exact class instead of the topAB {A*,A,B} proxy.
                    .put("category", result.getCoreRankingEquivalent() != null ? result.getCoreRankingEquivalent() : "")
                    // S2 position-aware scoring: the target position under evaluation ("CONF_UNIV"/…), or "" on
                    // the canonical pass — so a formula's non-position branch defines the canonical score (the
                    // FV Info 2016 ruling: category-D conference points count canonically and for Asist/Lect,
                    // and are cut only when Poz is CONF_UNIV/PROF_UNIV). Existing formulas never reference it.
                    .put("Poz", "");
            if (result.getMultiplier() != null) {
                builder.put("M", result.getMultiplier());
            }
            FormulaContext ctx = builder.build();

            long formulaEvalStartedAtNanos = System.nanoTime();
            double finalScore = formulaEvaluator.eval(indicator.getFormula(), ctx);
            long formulaEvalNanos = System.nanoTime() - formulaEvalStartedAtNanos;
            if (timing != null) {
                timing.addFormulaEvalNanos(formulaEvalNanos);
            }
            // Robustness guard: a non-finite per-publication score (e.g. a corrupt Infinity/NaN journal metric S, or
            // a 0-author divisor N in a formula like "S/N") must never poison the indicator total — it contributes 0
            // so the remaining valid publications still sum correctly. Surfaced by H77 provisional scoring (a RIS
            // forum metric projected as Infinity made whole-department reports show ∞).
            result.setAuthorScore(Double.isFinite(finalScore) ? finalScore : 0.0);

            // S2 position-aware scoring: a formula referencing Poz gets re-evaluated once per position (a
            // compiled-formula-cache MVEL run each — the expensive base-score pipeline above ran once).
            // Only positions whose value DIFFERS from the canonical score are stored, so items and totals
            // without position divergence carry nothing and downstream falls back to the canonical value.
            if (formulaReferencesPoz(indicator.getFormula())) {
                Map<String, Double> byPosition = new LinkedHashMap<>();
                for (Position position : Position.values()) {
                    FormulaContext.Builder positionBuilder = FormulaContext.builder();
                    ctx.variables().forEach((k, v) ->
                            positionBuilder.put(k, "Poz".equals(k) ? position.name() : v));
                    double positionScore = formulaEvaluator.eval(indicator.getFormula(), positionBuilder.build());
                    positionScore = Double.isFinite(positionScore) ? positionScore : 0.0;
                    if (positionScore != result.getAuthorScore()) {
                        byPosition.put(position.name(), positionScore);
                    }
                }
                if (!byPosition.isEmpty()) {
                    result.getScoringInfo().put(AUTHOR_SCORE_BY_POSITION, byPosition);
                }
            }

            // Counterfactual gate diagnosis: a categorized item (positive venue score) the formula
            // zeroed gets probed against the gate table so the drilldown can explain WHY instead of
            // showing a bare "formula cutoff". Each probe re-evaluates the formula with one gate
            // variable forced to its favorable value; the first probe that alone turns the score
            // positive proves that gate caused the zero. Probes are cheap: the compiled-formula
            // cache makes each re-eval a map lookup + MVEL run.
            if (result.getAuthorScore() == 0.0) {
                stampFormulaGateReason(indicator, ctx, result);
            }
        }
        return result;
    }

    /** One counterfactual gate probe: re-evaluate with {@code variable} forced to {@code favorableValue}. */
    private record GateProbe(String variable, Object favorableValue, String reason) {}

    /**
     * Ordered gate probes — specific gates first so their precise reason wins; the {@code S}
     * threshold probe last, as the generic catch-all for any {@code S > k ? … : 0}-shaped formula
     * (the 2016 conference D-gate {@code S > 1}, the praguri {@code S >= 4}, …). The sentinel is far
     * above any category ladder value, so it clears every expressible threshold.
     */
    private static final List<GateProbe> GATE_PROBES = List.of(
            new GateProbe("feeJournal", Boolean.FALSE, "FEE_JOURNAL"),
            new GateProbe("topAB", Boolean.TRUE, "NOT_TOP_RANKED"),
            new GateProbe("S", 1_000_000.0, "SCORE_BELOW_FORMULA_THRESHOLD")
    );

    /**
     * Stamps {@code scoringInfo.zeroReason} for a formula-zeroed item by counterfactual probing.
     * Single-gate probes run in table order; when none alone explains the zero, one combined probe
     * (every gate favorable at once) distinguishes "several gates failed together" (MULTIPLE_GATES)
     * from a zero the gate table cannot model at all, which stays honestly unstamped.
     */
    private void stampFormulaGateReason(Indicator indicator, FormulaContext ctx, Score result) {
        Map<String, Object> vars = ctx.variables();
        for (GateProbe gate : GATE_PROBES) {
            Object current = vars.get(gate.variable());
            if (current == null || gate.favorableValue().equals(current)) {
                continue; // gate variable unbound or already favorable — cannot be the cause
            }
            if (probeTurnsPositive(indicator, vars, Map.of(gate.variable(), gate.favorableValue()))) {
                result.getScoringInfo().put("zeroReason", gate.reason());
                return;
            }
        }
        Map<String, Object> allFavorable = new LinkedHashMap<>();
        for (GateProbe gate : GATE_PROBES) {
            Object current = vars.get(gate.variable());
            if (current != null && !gate.favorableValue().equals(current)) {
                allFavorable.put(gate.variable(), gate.favorableValue());
            }
        }
        if (allFavorable.size() > 1 && probeTurnsPositive(indicator, vars, allFavorable)) {
            result.getScoringInfo().put("zeroReason", "MULTIPLE_GATES");
        }
    }

    private boolean probeTurnsPositive(Indicator indicator, Map<String, Object> vars, Map<String, Object> overrides) {
        FormulaContext.Builder probeBuilder = FormulaContext.builder();
        vars.forEach((k, v) -> probeBuilder.put(k, overrides.getOrDefault(k, v)));
        double probe = formulaEvaluator.eval(indicator.getFormula(), probeBuilder.build());
        return Double.isFinite(probe) && probe > 0.0;
    }

    public Map<String, Score> precomputeCitationBaseScores(List<? extends ScoringPublicationReadModel> citingPublications, Indicator indicator) {
        if (citingPublications == null || citingPublications.isEmpty()) {
            return Map.of();
        }
        // H52 slice 11d.1: typed-strategy check. GenericCount has no per-publication
        // base scoring, so the citation cache stays empty. Also guards an indicator
        // with no resolvable kind (legacy strategy=null was a real guarded case).
        if (indicator == null || indicator.getEffectiveKind() == null || indicator.isGenericCount()) {
            return Map.of();
        }
        ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());
        if (scoringService == null) {
            return Map.of();
        }
        Map<String, Score> cached = new HashMap<>();
        for (ScoringPublicationReadModel citingPublication : citingPublications) {
            if (citingPublication == null || citingPublication.getId() == null || cached.containsKey(citingPublication.getId())) {
                continue;
            }
            // Mirror the universal subtype gate so non-research citing publications are
            // cached as zero rather than scored.
            Score baseScore = PublicationSubtypeSupport.isResearchContribution(citingPublication)
                    ? scoringService.getScore(citingPublication, indicator)
                    : new Score();
            cached.put(citingPublication.getId(), copyScore(baseScore));
        }
        return cached;
    }

    private Score copyScore(Score source) {
        if (source == null) {
            return new Score();
        }
        Score target = new Score();
        target.setScore(source.getScore());
        target.setYear(source.getYear());
        target.setCoreRankingEquivalent(source.getCoreRankingEquivalent());
        target.setQuarter(source.getQuarter());
        target.setScoringSource(source.getScoringSource());
        target.setScoringInfo(new HashMap<>(source.getScoringInfo() == null ? Map.of() : source.getScoringInfo()));
        target.setAuthorScore(source.getAuthorScore());
        // H52 slice 11c: typed multiplier propagates; extra/errors/details are gone.
        target.setMultiplier(source.getMultiplier());
        return target;
    }

    /**
     * H79: is this scored item eligible for the "top publications" criterion (category A-star / A / B)?
     *
     * <p>For a <b>workshop-adjusted</b> conference item the standard's category is authoritative — a 2026
     * workshop of an A*, A or B conference is category C (id_parA82) and must NOT count toward the top
     * (A-star / A / B) despite its inflated 6/4 points; a 2016 workshop keeps its one-lower category
     * (A*&rarr;A, A&rarr;B) and still qualifies. For
     * every other item (journals — category derived from points; normal conferences; SENSE-scale books) the
     * legacy {@code S>=4} threshold is exactly "category in {A*,A,B}" and is preserved verbatim, so book and
     * journal behaviour is unchanged and no unintended 2016↔2026 divergence is introduced.</p>
     */
    private static boolean isTopAStarAB(Score score) {
        boolean workshopAdjusted = score.getScoringInfo() != null
                && Boolean.TRUE.equals(score.getScoringInfo().get("workshopAdjusted"));
        if (workshopAdjusted) {
            String category = score.getCoreRankingEquivalent();
            return "A_STAR".equals(category) || "A".equals(category) || "B".equals(category);
        }
        return score.getScore() >= 4.0;
    }

    private long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private static class ScoreComputationTiming {
        private long baseScoreLookupNanos;
        private long formulaEvalNanos;

        void addBaseScoreLookupNanos(long value) {
            baseScoreLookupNanos += value;
        }

        void addFormulaEvalNanos(long value) {
            formulaEvalNanos += value;
        }

        long baseScoreLookupNanos() {
            return baseScoreLookupNanos;
        }

        long formulaEvalNanos() {
            return formulaEvalNanos;
        }
    }


}
