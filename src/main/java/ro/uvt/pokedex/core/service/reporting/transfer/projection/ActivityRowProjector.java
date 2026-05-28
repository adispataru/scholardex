package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Projects an activity-typed indicator's scored entries into {@link ActivitySnapshotItem}s for the
 * {@code activities-perspectiva-d} role. Each indicator carries a single {@link Activity} DBRef
 * whose {@code name} becomes the {@code activityName} the renderer uses to bucket rows into the
 * binding's sub-blocks.
 *
 * <p>Score values inside the rawGraph may arrive either as live {@link ro.uvt.pokedex.core.service.reporting.Score}
 * objects (freshly computed path) or as plain {@code Map}s (deserialized-from-DB path); the extractors
 * here tolerate both.
 */
@Component
public class ActivityRowProjector {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityRowProjector.class);

    public static final String ROLE_KEY = "activities-perspectiva-d";

    private final UserIndicatorResultService userIndicatorResultService;

    public ActivityRowProjector(UserIndicatorResultService userIndicatorResultService) {
        this.userIndicatorResultService = userIndicatorResultService;
    }

    public List<ActivitySnapshotItem> project(String userEmail, Indicator indicator, String roleKey) {
        if (!ROLE_KEY.equals(roleKey)) return List.of();
        Activity activity = indicator.getActivity();
        if (activity == null) return List.of();
        String activityName = activity.getName();
        if (activityName == null || activityName.isBlank()) {
            LOG.warn("Indicator {} mapped to {} has no Activity.name; skipping", indicator.getId(), ROLE_KEY);
            return List.of();
        }

        IndicatorApplyResultDto result = userIndicatorResultService.getOrCreateLatest(userEmail, indicator.getId());
        Map<String, Object> graph = result.rawGraph();
        if (graph == null) return List.of();

        Object outputMode = graph.get("outputMode");
        if (outputMode != null && !"activities".equals(outputMode.toString())) {
            return List.of();
        }

        Object scoresObj = graph.get("scores");
        if (!(scoresObj instanceof Map<?, ?> actScores)) return List.of();
        Object activitiesObj = graph.get("activities");

        List<ActivitySnapshotItem> out = new ArrayList<>();
        for (Map.Entry<?, ?> entry : actScores.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object scoreObj = entry.getValue();
            double score = extractAuthorScore(scoreObj);
            if (score <= 0) continue;

            ActivitySnapshotItem item = new ActivitySnapshotItem();
            item.setRoleKey(roleKey);
            item.setItemKey(activityName + ":" + key);
            item.setActivityName(activityName);
            item.setDescription(resolveDescription(scoreObj, activitiesObj, key));
            item.setCategory(extractCoreRankingEquivalent(scoreObj));
            item.setScore(score);
            out.add(item);
        }
        return out;
    }

    private String resolveDescription(Object scoreObj, Object activitiesObj, String activityId) {
        String details = extractDetails(scoreObj);
        if (details != null && !details.isBlank()) return details;
        String label = resolveActivityLabel(activitiesObj, activityId);
        return label != null ? label : activityId;
    }

    private double extractAuthorScore(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Map<?, ?> m) {
            Object v = m.get("authorScore");
            return v instanceof Number n ? n.doubleValue() : 0.0;
        }
        try {
            return (double) obj.getClass().getMethod("getAuthorScore").invoke(obj);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String extractDetails(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Map<?, ?> m) {
            Object v = m.get("details");
            return v != null ? v.toString() : null;
        }
        try {
            Object v = obj.getClass().getMethod("getDetails").invoke(obj);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractCoreRankingEquivalent(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Map<?, ?> m) {
            Object v = m.get("coreRankingEquivalent");
            return v != null ? v.toString() : null;
        }
        try {
            Object v = obj.getClass().getMethod("getCoreRankingEquivalent").invoke(obj);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveActivityLabel(Object activitiesObj, String activityId) {
        if (activitiesObj instanceof Map<?, ?> activitiesMap) {
            Object byId = activitiesMap.get(activityId);
            if (byId instanceof Map<?, ?> attrs) {
                Object name = attrs.get("name");
                if (name != null) return name.toString();
            }
            if (byId != null) return byId.toString();
        }
        if (activitiesObj instanceof List<?> activities) {
            for (Object act : activities) {
                if (act instanceof Map<?, ?> attrs) {
                    Object id = attrs.get("id");
                    if (id != null && activityId.equals(id.toString())) {
                        Object name = attrs.get("name");
                        return name != null ? name.toString() : null;
                    }
                } else {
                    try {
                        Object id = act.getClass().getMethod("getId").invoke(act);
                        if (id != null && activityId.equals(id.toString())) {
                            Object name = act.getClass().getMethod("getName").invoke(act);
                            return name != null ? name.toString() : null;
                        }
                    } catch (Exception ignored) { /* fall through */ }
                }
            }
        }
        return null;
    }
}
