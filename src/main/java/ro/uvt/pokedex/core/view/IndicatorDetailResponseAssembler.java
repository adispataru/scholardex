package ro.uvt.pokedex.core.view;

import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
                             String publicationId, String forumId) {}

    /** Publication/forum ids resolved for a scored item; both null when the title join is ambiguous. */
    private record PubLink(String publicationId, String forumId) {
        static final PubLink NONE = new PubLink(null, null);
    }

    public record CitationDetailResponse(String pubTitle, double totalScore, List<ScoredItem> citations) {}

    public static IndicatorDetailResponse buildDetail(IndicatorApplyResultDto result) {
        Map<String, Object> graph = result.rawGraph();
        String outputMode = graph.getOrDefault("outputMode", "publications").toString();
        List<ScoredItem> items = extractScoredItems(graph, outputMode);
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
                    if (authorScore > 0) {
                        // Citing papers are third-party publications — no ids in the graph to link.
                        citations.add(new ScoredItem(
                                entry.getKey().toString(),
                                extractYear(entry.getValue()),
                                authorScore, forumScore,
                                extractQuarter(entry.getValue()),
                                extractCoreRankingEquivalent(entry.getValue()),
                                extractScoringSource(entry.getValue()),
                                "publication", null, null, null));
                        total += authorScore;
                    }
                }
            }
        }
        citations.sort(Comparator.comparingDouble(ScoredItem::authorScore).reversed());
        return new CitationDetailResponse(pubTitle, total, citations);
    }

    private static List<ScoredItem> extractScoredItems(Map<String, Object> graph, String outputMode) {
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
                        if (authorScore > 0) {
                            PubLink link = linkFor(pubsByTitle, pubTitle);
                            items.add(new ScoredItem(pubTitle, extractYear(totalObj), authorScore, forumScore,
                                    extractQuarter(totalObj), extractCoreRankingEquivalent(totalObj),
                                    extractScoringSource(totalObj), "citation", null,
                                    link.publicationId(), link.forumId()));
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
                        String label = resolveActivityLabel(activitiesObj, key);
                        items.add(new ScoredItem(label != null ? label : key, extractYear(entry.getValue()),
                                authorScore, forumScore, extractQuarter(entry.getValue()),
                                extractCoreRankingEquivalent(entry.getValue()),
                                extractScoringSource(entry.getValue()), "activity", extractDetails(entry.getValue()),
                                null, null));
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
                        items.add(new ScoredItem(entry.getKey().toString(), extractYear(entry.getValue()),
                                authorScore, forumScore, extractQuarter(entry.getValue()),
                                extractCoreRankingEquivalent(entry.getValue()),
                                extractScoringSource(entry.getValue()), "publication", null,
                                link.publicationId(), link.forumId()));
                    }
                }
            }
        }
        return items;
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

    @SuppressWarnings("unchecked")
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
        Object ind = graph.get("indicator");
        if (ind == null) return null;
        try {
            Object name = ind.getClass().getMethod("getName").invoke(ind);
            return name != null ? name.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String outputTypeFrom(Map<String, Object> graph) {
        Object ind = graph.get("indicator");
        if (ind == null) return null;
        try {
            Object ot = ind.getClass().getMethod("getOutputType").invoke(ind);
            return ot != null ? ot.toString() : null;
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
