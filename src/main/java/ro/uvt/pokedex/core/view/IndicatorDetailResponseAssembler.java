package ro.uvt.pokedex.core.view;

import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the JSON responses for the indicator-detail and citation-drilldown endpoints from a
 * computed {@link IndicatorApplyResultDto}.
 *
 * <p>Shared by the researcher's own drilldown ({@code EvaluationWorkspaceController}) and the
 * delegated admin/supervisor drilldown ({@code ResearcherReportController}) so both emit byte-for-byte
 * identical payloads — the only difference between the two surfaces is how the DTO is obtained
 * (self may write a LATEST cache row; delegated stays strictly read-only) and the URL prefix the
 * client fetches from. The mapping itself lives here, once.
 *
 * <p>Pure functions of the DTO/raw-graph: no Spring beans, no persistence, no principal.
 */
public final class IndicatorDetailResponseAssembler {

    private IndicatorDetailResponseAssembler() {
    }

    public record IndicatorDetailResponse(
            String indicatorId,
            String indicatorName,
            String outputType,
            String outputMode,
            double totalScore,
            Double totalCitations,
            List<String> quarterLabels,
            List<Integer> quarterValues,
            List<ScoredItem> items,
            Instant updatedAt,
            int refreshVersion
    ) {}

    public record ScoredItem(String key, int year, double authorScore, double forumScore,
                             String quarter, String coreRankingEquivalent, String scoringSource,
                             String type, String details,
                             /** Canonical ids for detail-page links; null when not resolvable (see title join). */
                             String publicationId, String forumId,
                             /** Why a gate zeroed/dropped this item (EXCLUDED_VENUE, …); null for scored items. */
                             String zeroReason,
                             /** Forum display name for the forum link; null when unresolved. */
                             String forumName,
                             /** APC/fee-journal venue fact (always shown as a badge, gate or no gate). */
                             boolean feeJournal,
                             /** Comma-joined gate reasons behind a MULTIPLE_GATES zero; null otherwise. */
                             String gateCauses) {}

    /** Publication/forum ids resolved for a scored item; both null when the title join is ambiguous. */
    private record PubLink(String publicationId, String forumId) {
        static final PubLink NONE = new PubLink(null, null);
    }

    public record CitationDetailResponse(String pubTitle, double totalScore, List<ScoredItem> citations) {}

    public static IndicatorDetailResponse buildDetail(IndicatorApplyResultDto result) {
        return buildDetail(result, id -> null, Function.identity());
    }

    /**
     * @param forumNameResolver forum id → display name for the evidence table's forum links (the
     *   scores map only carries ids); returning null leaves the item's forumName unset. Passed in
     *   so the assembler stays a pure function of the DTO (no Spring beans).
     */
    public static IndicatorDetailResponse buildDetail(
            IndicatorApplyResultDto result, Function<String, String> forumNameResolver) {
        return buildDetail(result, forumNameResolver, Function.identity());
    }

    /**
     * @param projectLabelResolver PROJECT_GRANT_ID reference (a {@code sproj_…} canonical id) → trusted
     *   display label (mirrors {@code ActivityBlockProjector.resolveProjectLabel}, the export/import
     *   flow's resolution, so the evidence panel and an exported FV describe the same activity
     *   identically). Returning the input unchanged leaves the raw reference id in the description.
     */
    public static IndicatorDetailResponse buildDetail(
            IndicatorApplyResultDto result, Function<String, String> forumNameResolver,
            Function<String, String> projectLabelResolver) {
        Map<String, Object> graph = result.rawGraph();
        String outputMode = graph.getOrDefault("outputMode", "publications").toString();
        List<ScoredItem> items = extractScoredItems(graph, outputMode, forumNameResolver, projectLabelResolver);
        return new IndicatorDetailResponse(
                result.indicatorId(),
                indicatorNameFrom(graph),
                outputTypeFrom(graph),
                outputMode,
                result.summary().totalScore(),
                totalCitFrom(graph),
                result.summary().quarterLabels(),
                result.summary().quarterValues(),
                items,
                result.updatedAt(),
                result.refreshVersion());
    }

    public static CitationDetailResponse buildCitations(IndicatorApplyResultDto result, String pubTitle) {
        Map<String, Object> graph = result.rawGraph();
        List<ScoredItem> citations = new ArrayList<>();
        double total = 0.0;

        Object scoresObj = graph.get("scores");
        if (scoresObj instanceof Map<?, ?> pubScores) {
            Object citMapObj = pubScores.get(pubTitle);
            if (citMapObj instanceof Map<?, ?> citScores) {
                for (Map.Entry<?, ?> entry : citScores.entrySet()) {
                    if ("total".equals(entry.getKey().toString())) continue;
                    double authorScore = extractAuthorScore(entry.getValue());
                    double forumScore = extractForumScore(entry.getValue());
                    String zeroReason = extractZeroReason(entry.getValue());
                    // Show every citing paper that was correctly categorized (positive venue score),
                    // even when a 2026 formula gate (FEE_JOURNAL, NOT_TOP_RANKED, …) zeroed the author
                    // score — mirrors the publications table's authorScore>0||forumScore>0 rule so a
                    // gated citation is muted with its reason instead of silently vanishing. A citing
                    // paper the self-citation policy excluded upstream (SELF_CITATION) never gets a
                    // venue score at all — the zeroReason clause is what surfaces it here.
                    if (authorScore > 0 || forumScore > 0 || zeroReason != null) {
                        // Citing papers are third-party publications — no ids in the graph to link.
                        citations.add(new ScoredItem(
                                entry.getKey().toString(),
                                extractYear(entry.getValue()),
                                authorScore, forumScore,
                                extractQuarter(entry.getValue()),
                                extractCoreRankingEquivalent(entry.getValue()),
                                extractScoringSource(entry.getValue()),
                                "publication", null, null, null,
                                zeroReason, null,
                                extractFeeJournal(entry.getValue()), extractGateCauses(entry.getValue())));
                        total += authorScore;
                    }
                }
            }
        }
        citations.sort(Comparator.comparingDouble(ScoredItem::authorScore).reversed());
        return new CitationDetailResponse(pubTitle, total, citations);
    }

    private static List<ScoredItem> extractScoredItems(
            Map<String, Object> graph, String outputMode,
            Function<String, String> forumNameResolver, Function<String, String> projectLabelResolver) {
        Object scoresObj = graph.get("scores");
        if (scoresObj == null) return List.of();

        Map<String, List<Object>> pubsByTitle = publicationsByTitle(graph);
        List<ScoredItem> items = new ArrayList<>();
        if ("citations".equals(outputMode)) {
            if (scoresObj instanceof Map<?, ?> pubScores) {
                for (Map.Entry<?, ?> pubEntry : pubScores.entrySet()) {
                    String pubTitle = pubEntry.getKey().toString();
                    if (pubEntry.getValue() instanceof Map<?, ?> citScores) {
                        Object totalObj = citScores.get("total");
                        double authorScore = extractAuthorScore(totalObj);
                        double forumScore = extractForumScore(totalObj);
                        // A cited publication whose citations were ALL gated to zero (e.g. every citer was
                        // fee-journal/below the top rank) still has a positive aggregate forum score — keep
                        // showing the row (0 total) instead of vanishing it, so the drilldown modal (which
                        // now surfaces each citer's mute reason) is reachable at all.
                        if (authorScore > 0 || forumScore > 0) {
                            PubLink link = linkFor(pubsByTitle, pubTitle);
                            items.add(new ScoredItem(pubTitle, extractYear(totalObj), authorScore, forumScore,
                                    extractQuarter(totalObj), extractCoreRankingEquivalent(totalObj),
                                    extractScoringSource(totalObj), "citation", null,
                                    link.publicationId(), link.forumId(), null,
                                    forumNameResolver.apply(link.forumId()),
                                    false, null));
                        }
                    }
                }
            }
        } else if ("activities".equals(outputMode)) {
            Object activitiesObj = graph.get("activities");
            if (scoresObj instanceof Map<?, ?> actScores) {
                for (Map.Entry<?, ?> entry : actScores.entrySet()) {
                    String key = entry.getKey().toString();
                    double authorScore = extractAuthorScore(entry.getValue());
                    double forumScore = extractForumScore(entry.getValue());
                    if (authorScore > 0) {
                        String description = resolveActivityDescription(
                                activitiesObj, key, entry.getValue(), projectLabelResolver);
                        items.add(new ScoredItem(description, extractYear(entry.getValue()),
                                authorScore, forumScore, extractQuarter(entry.getValue()),
                                extractCoreRankingEquivalent(entry.getValue()),
                                extractScoringSource(entry.getValue()), "activity", extractDetails(entry.getValue()),
                                null, null, null, null, false, null));
                    }
                }
            }
        } else {
            if (scoresObj instanceof Map<?, ?> pubScores) {
                for (Map.Entry<?, ?> entry : pubScores.entrySet()) {
                    if ("total".equals(entry.getKey().toString())) continue;
                    double authorScore = extractAuthorScore(entry.getValue());
                    double forumScore = extractForumScore(entry.getValue());
                    // Show every publication that was correctly categorized for this indicator —
                    // a positive base/forum score means it matched the indicator's domain/selector,
                    // even when the scoring formula drove the author score to 0. Without the
                    // forumScore check these formula-zeroed items would vanish from the drilldown
                    // despite being valid, categorized publications. (Non-categorized publications
                    // never reach this map: ScientificProductionService drops base==0 entries.)
                    if (authorScore > 0 || forumScore > 0) {
                        PubLink link = linkFor(pubsByTitle, entry.getKey().toString());
                        // In-map items can carry a zeroReason too (e.g. FEE_JOURNAL: positive venue
                        // points, formula-zeroed by the APC gate) — flow it, don't assume null.
                        items.add(new ScoredItem(entry.getKey().toString(), extractYear(entry.getValue()),
                                authorScore, forumScore, extractQuarter(entry.getValue()),
                                extractCoreRankingEquivalent(entry.getValue()),
                                extractScoringSource(entry.getValue()), "publication", null,
                                link.publicationId(), link.forumId(), extractZeroReason(entry.getValue()),
                                forumNameResolver.apply(link.forumId()),
                                extractFeeJournal(entry.getValue()), extractGateCauses(entry.getValue())));
                    }
                }
            }
            // Gate-dropped publications (excluded venue, non-research subtype, role filter, top-N)
            // are appended after the scored items with the reason the gate recorded, so the
            // drilldown explains a zero instead of silently hiding the publication.
            Object excludedObj = graph.get("excludedItems");
            if (excludedObj instanceof Map<?, ?> excluded) {
                for (Map.Entry<?, ?> entry : excluded.entrySet()) {
                    PubLink link = linkFor(pubsByTitle, entry.getKey().toString());
                    items.add(new ScoredItem(entry.getKey().toString(), extractYear(entry.getValue()),
                            0.0, extractForumScore(entry.getValue()), extractQuarter(entry.getValue()),
                            extractCoreRankingEquivalent(entry.getValue()),
                            extractScoringSource(entry.getValue()), "publication", null,
                            link.publicationId(), link.forumId(), extractZeroReason(entry.getValue()),
                            forumNameResolver.apply(link.forumId()),
                            extractFeeJournal(entry.getValue()), extractGateCauses(entry.getValue())));
                }
            }
        }
        return items;
    }

    /** Reads scoringInfo.zeroReason from a Score bean or its cached plain-map form. */
    private static String extractZeroReason(Object scoreObj) {
        if (scoreObj == null) return null;
        Object scoringInfo;
        if (scoreObj instanceof Map<?, ?> map) {
            scoringInfo = map.get("scoringInfo");
        } else {
            try {
                scoringInfo = scoreObj.getClass().getMethod("getScoringInfo").invoke(scoreObj);
            } catch (Exception e) {
                return null;
            }
        }
        if (scoringInfo instanceof Map<?, ?> info) {
            Object reason = info.get("zeroReason");
            return reason != null ? reason.toString() : null;
        }
        return null;
    }

    /** Generic scoringInfo reader (bean or cached plain-map form); null when absent. */
    private static Object extractScoringInfoValue(Object scoreObj, String key) {
        if (scoreObj == null) return null;
        Object scoringInfo;
        if (scoreObj instanceof Map<?, ?> map) {
            scoringInfo = map.get("scoringInfo");
        } else {
            try {
                scoringInfo = scoreObj.getClass().getMethod("getScoringInfo").invoke(scoreObj);
            } catch (Exception e) {
                return null;
            }
        }
        return scoringInfo instanceof Map<?, ?> info ? info.get(key) : null;
    }

    /** APC/fee-journal venue fact stamped by the scorer; false when absent (pre-badge cached results). */
    private static boolean extractFeeJournal(Object scoreObj) {
        return Boolean.TRUE.equals(extractScoringInfoValue(scoreObj, "feeJournal"))
                || "true".equals(String.valueOf(extractScoringInfoValue(scoreObj, "feeJournal")));
    }

    /** Comma-joined gate reasons behind a MULTIPLE_GATES zero; null when absent. */
    private static String extractGateCauses(Object scoreObj) {
        Object causes = extractScoringInfoValue(scoreObj, "gateCauses");
        return causes != null ? causes.toString() : null;
    }

    /**
     * The scores map is keyed by publication TITLE, so ids are recovered by joining against the
     * graph's {@code publications} list. The join only links a title that maps to exactly one
     * publication (duplicate titles stay unlinked rather than risking the wrong page). On the
     * LATEST/apply path the persisted list holds only authorScore>0 publications, so formula-zeroed
     * items resolve no link there; the report-scoped path keeps every filtered publication.
     */
    private static Map<String, List<Object>> publicationsByTitle(Map<String, Object> graph) {
        Object pubsObj = graph.get("publications");
        if (!(pubsObj instanceof List<?> pubs)) return Map.of();
        Map<String, List<Object>> byTitle = new java.util.HashMap<>();
        for (Object pub : pubs) {
            String title = pubProperty(pub, "title");
            if (title != null) {
                byTitle.computeIfAbsent(title, k -> new ArrayList<>()).add(pub);
            }
        }
        return byTitle;
    }

    private static PubLink linkFor(Map<String, List<Object>> pubsByTitle, String title) {
        List<Object> matches = pubsByTitle.get(title);
        if (matches == null || matches.size() != 1) return PubLink.NONE;
        Object pub = matches.get(0);
        String forumId = pubProperty(pub, "forumId");
        if (forumId == null) forumId = pubProperty(pub, "forum");
        return new PubLink(pubProperty(pub, "id"), forumId);
    }

    /** Publications arrive as live view beans (report-scoped path) or plain maps (cached LATEST blobs). */
    private static String pubProperty(Object pub, String prop) {
        if (pub == null) return null;
        if (pub instanceof Map<?, ?> map) {
            Object v = map.get(prop);
            return v != null ? v.toString() : null;
        }
        try {
            Object v = pub.getClass()
                    .getMethod("get" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1))
                    .invoke(pub);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static double extractAuthorScore(Object scoreObj) {
        if (scoreObj == null) return 0.0;
        try {
            return (double) scoreObj.getClass().getMethod("getAuthorScore").invoke(scoreObj);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static double extractForumScore(Object scoreObj) {
        if (scoreObj == null) return 0.0;
        try {
            return (double) scoreObj.getClass().getMethod("getScore").invoke(scoreObj);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String extractQuarter(Object scoreObj) {
        if (scoreObj == null) return null;
        try {
            Object q = scoreObj.getClass().getMethod("getQuarter").invoke(scoreObj);
            return q != null ? q.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractDetails(Object scoreObj) {
        if (scoreObj == null) return null;
        try {
            Object d = scoreObj.getClass().getMethod("getDetails").invoke(scoreObj);
            return d != null ? d.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int extractYear(Object scoreObj) {
        if (scoreObj == null) return 0;
        try {
            return (int) scoreObj.getClass().getMethod("getYear").invoke(scoreObj);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String extractCoreRankingEquivalent(Object scoreObj) {
        if (scoreObj == null) return null;
        try {
            Object v = scoreObj.getClass().getMethod("getCoreRankingEquivalent").invoke(scoreObj);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractScoringSource(Object scoreObj) {
        if (scoreObj == null) return null;
        try {
            Object v = scoreObj.getClass().getMethod("getScoringSource").invoke(scoreObj);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final String PROJECT_GRANT_REF = Activity.ReferenceField.PROJECT_GRANT_ID.name();

    /**
     * Describes an evidence row for an activity-output indicator. Mirrors
     * {@code ActivityBlockProjector.resolveDescription}'s tiered fallback (rich description → bare
     * name → Score.details → raw id) so the evidence panel reads the same as an exported FV, instead
     * of falling straight to the raw ActivityInstance id — the {@code activities} raw-graph list is a
     * plain {@code Map} on every path except the very first, not-yet-persisted compute (still handled
     * via reflection as the bare-name tier), so a Map-only rich builder covers the practical case.
     */
    private static String resolveActivityDescription(
            Object activitiesObj, String activityId, Object scoreObj, Function<String, String> projectLabelResolver) {
        String built = buildActivityInstanceDescription(activitiesObj, activityId, projectLabelResolver);
        if (built != null && !built.isBlank()) return built;
        String label = resolveActivityLabel(activitiesObj, activityId);
        if (label != null && !label.isBlank() && !label.equals(activityId)) return label;
        String details = extractDetails(scoreObj);
        return details != null && !details.isBlank() ? details : activityId;
    }

    @SuppressWarnings("unchecked")
    private static String buildActivityInstanceDescription(
            Object activitiesObj, String activityId, Function<String, String> projectLabelResolver) {
        Map<String, Object> attrs = findActivityInstanceMap(activitiesObj, activityId);
        if (attrs == null) return null;

        List<String> parts = new ArrayList<>();
        Object name = attrs.get("name");
        if (name != null && !name.toString().isBlank()) parts.add(name.toString());

        Object fields = attrs.get("fields");
        if (fields instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (v != null && !v.toString().isBlank()) {
                    parts.add(e.getKey() + ": " + v);
                }
            }
        }

        Object refFields = attrs.get("referenceFields");
        if (refFields instanceof Map<?, ?> rm) {
            for (Map.Entry<?, ?> e : rm.entrySet()) {
                Object v = e.getValue();
                if (v == null || v.toString().isBlank()) {
                    continue;
                }
                if (PROJECT_GRANT_REF.equals(String.valueOf(e.getKey()))) {
                    parts.add(projectLabelResolver.apply(v.toString()));
                } else {
                    parts.add(v.toString());
                }
            }
        }

        Object date = attrs.get("date");
        if (date != null && !date.toString().isBlank()) parts.add("(" + date + ")");

        return parts.isEmpty() ? null : String.join(" — ", parts);
    }

    /** The activity instance's own map, matched by id — the shape {@code activities} carries once
     *  a report run has been persisted and read back (every practical case; see resolveActivityDescription). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findActivityInstanceMap(Object activitiesObj, String activityId) {
        if (activitiesObj instanceof List<?> activities) {
            for (Object act : activities) {
                if (act instanceof Map<?, ?> attrs) {
                    Object id = attrs.get("id");
                    if (id != null && activityId.equals(id.toString())) {
                        return (Map<String, Object>) attrs;
                    }
                }
            }
        }
        return null;
    }

    /** Bare-name fallback for the rare case {@code activities} is still a live bean list (fresh, not-yet-persisted compute). */
    private static String resolveActivityLabel(Object activitiesObj, String activityId) {
        if (activitiesObj instanceof List<?> activities) {
            for (Object act : activities) {
                try {
                    Object id = act.getClass().getMethod("getId").invoke(act);
                    if (activityId.equals(id != null ? id.toString() : null)) {
                        Object name = act.getClass().getMethod("getName").invoke(act);
                        return name != null ? name.toString() : activityId;
                    }
                } catch (Exception ignored) { /* fall through */ }
            }
        }
        return activityId;
    }

    private static String indicatorNameFrom(Map<String, Object> graph) {
        return indicatorPropertyFrom(graph, "getName", "name");
    }

    private static String outputTypeFrom(Map<String, Object> graph) {
        return indicatorPropertyFrom(graph, "getOutputType", "outputType");
    }

    /**
     * The graph's indicator is a live {@code Indicator} bean on the compute path but a plain map after a
     * persisted-snapshot round-trip (the payload serializer stores it as JSON) — read both forms, like
     * the Score handling elsewhere in this assembler.
     */
    private static String indicatorPropertyFrom(Map<String, Object> graph, String getter, String mapKey) {
        Object ind = graph.get("indicator");
        if (ind == null) return null;
        if (ind instanceof Map<?, ?> map) {
            Object value = map.get(mapKey);
            return value != null ? value.toString() : null;
        }
        try {
            Object value = ind.getClass().getMethod(getter).invoke(ind);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double totalCitFrom(Map<String, Object> graph) {
        Object tc = graph.get("totalCit");
        if (tc == null) return null;
        return tc instanceof Number n ? n.doubleValue() : null;
    }
}
