package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingBlock;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportExportReadinessValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Projects the report's per-block indicator bindings into {@link ActivitySnapshotItem}s — one row
 * per scored entry per block. The block's source can be an activity-typed indicator (rawGraph
 * outputMode={@code activities}) OR a publication-typed indicator (rawGraph outputMode={@code
 * publications}); the projector dispatches by {@link Indicator#getOutputType()}.
 */
@Component
public class ActivityBlockProjector {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityBlockProjector.class);

    private final UserIndicatorResultService userIndicatorResultService;
    private final PublicationRowProjector publicationRowProjector;

    public ActivityBlockProjector(UserIndicatorResultService userIndicatorResultService,
                                  PublicationRowProjector publicationRowProjector) {
        this.userIndicatorResultService = userIndicatorResultService;
        this.publicationRowProjector = publicationRowProjector;
    }

    public List<ActivitySnapshotItem> projectAllBlocks(String userEmail,
                                                       IndividualReport report,
                                                       TemplateBinding binding) {
        List<ActivitySnapshotItem> out = new ArrayList<>();
        Map<String, List<Indicator>> indicatorsByBlock = buildIndicatorsByBlock(report);

        for (BindingRole role : binding.getRoles()) {
            if (role.getKind() != BindingKind.STACKED_BLOCKS) continue;
            for (BindingBlock block : role.getBlocks()) {
                List<Indicator> indicators = indicatorsByBlock.getOrDefault(block.getActivityName(), List.of());
                for (Indicator indicator : indicators) {
                    out.addAll(projectBlock(userEmail, indicator, role.getRoleKey(), block));
                }
            }
        }
        return out;
    }

    /**
     * Inverts {@code report.blockByIndicatorId} into a block→indicators view so multiple indicators
     * can feed the same block. Also folds in the legacy auto-match by {@code Indicator.activity.name}
     * for indicators that have no explicit block assignment.
     */
    private Map<String, List<Indicator>> buildIndicatorsByBlock(IndividualReport report) {
        Map<String, List<Indicator>> out = new java.util.LinkedHashMap<>();
        if (report.getIndicators() == null) return out;
        Map<String, String> assigned = report.getBlockByIndicatorId() != null
                ? report.getBlockByIndicatorId() : Map.of();

        for (Indicator indicator : report.getIndicators()) {
            if (indicator == null || indicator.getId() == null) continue;
            if (ReportExportReadinessValidator.isExcludedFromTemplate(report, indicator.getId())) continue;
            String blockName = assigned.get(indicator.getId());
            if (blockName == null || blockName.isBlank()) {
                // Legacy fallback for indicators that pre-date the explicit map.
                Activity a = indicator.getActivity();
                if (a != null && a.getName() != null) blockName = a.getName();
            }
            if (blockName == null || blockName.isBlank()) continue;
            out.computeIfAbsent(blockName, k -> new ArrayList<>()).add(indicator);
        }
        return out;
    }

    private List<ActivitySnapshotItem> projectBlock(String userEmail, Indicator indicator,
                                                    String roleKey, BindingBlock block) {
        // H52 slice 11d.2: dispatch on typed kind. Publication- and activity-shaped
        // indicators take separate code paths; citations have no projection support
        // here (the legacy enum's CITATIONS_* cases fell through the default arm).
        if (indicator.isPublicationOutput()) {
            return projectPublicationRawGraph(userEmail, indicator, roleKey, block);
        }
        if (indicator.isActivityOutput()) {
            return projectActivityRawGraph(userEmail, indicator, roleKey, block);
        }
        LOG.debug("Block '{}': indicator {} has unsupported output kind {} — no projection support; skipping",
                block.getActivityName(), indicator.getId(), indicator.getEffectiveKind());
        return List.of();
    }

    private List<ActivitySnapshotItem> projectPublicationRawGraph(String userEmail, Indicator indicator,
                                                                  String roleKey, BindingBlock block) {
        // Delegate to PublicationRowProjector so the description (C), category (H) and score (K)
        // all come from the same Score object — avoids any title-key drift between this projector
        // and a re-fetched rawGraph.
        List<PublicationSnapshotItem> pubs = publicationRowProjector.projectAllScored(userEmail, indicator, roleKey);
        List<ActivitySnapshotItem> out = new ArrayList<>();
        for (PublicationSnapshotItem pub : pubs) {
            ActivitySnapshotItem item = new ActivitySnapshotItem();
            item.setRoleKey(roleKey);
            item.setActivityName(block.getActivityName());
            item.setActivityId(indicator.getActivity() != null ? indicator.getActivity().getId() : null);
            item.setItemKey(block.getActivityName() + ":" + (pub.getItemKey() != null ? pub.getItemKey() : pub.getTitle()));
            item.setDescription(formatPublicationDescription(pub));
            item.setCategory(pub.getForumCategoryLetter());
            item.setScore(pub.getAuthorScore() != null ? pub.getAuthorScore() : 0.0);
            out.add(item);
        }
        return out;
    }

    private String formatPublicationDescription(PublicationSnapshotItem pub) {
        StringBuilder sb = new StringBuilder();
        if (pub.getTitle() != null && !pub.getTitle().isBlank()) sb.append(pub.getTitle());
        if (pub.getAuthors() != null && !pub.getAuthors().isBlank()) sb.append(" — ").append(pub.getAuthors());
        if (pub.getForumName() != null && !pub.getForumName().isBlank()) sb.append(" — ").append(pub.getForumName());
        if (pub.getVolumeInfo() != null && !pub.getVolumeInfo().isBlank()) sb.append(", ").append(pub.getVolumeInfo());
        if (pub.getYear() != null) sb.append(" (").append(pub.getYear()).append(')');
        return sb.toString();
    }

    private List<ActivitySnapshotItem> projectActivityRawGraph(String userEmail, Indicator indicator,
                                                               String roleKey, BindingBlock block) {
        IndicatorApplyResultDto result = userIndicatorResultService.getOrCreateLatest(userEmail, indicator.getId());
        Map<String, Object> graph = result.rawGraph();
        if (graph == null) return List.of();
        if (!(graph.get("scores") instanceof Map<?, ?> scores)) return List.of();
        Object activitiesObj = graph.get("activities");

        List<ActivitySnapshotItem> out = new ArrayList<>();
        for (Map.Entry<?, ?> entry : scores.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            double score = extractAuthorScore(value);
            if (score <= 0) continue;

            ActivitySnapshotItem item = new ActivitySnapshotItem();
            item.setRoleKey(roleKey);
            item.setActivityName(block.getActivityName());
            item.setActivityId(indicator.getActivity() != null ? indicator.getActivity().getId() : null);
            item.setItemKey(block.getActivityName() + ":" + key);
            item.setDescription(resolveDescription(value, activitiesObj, key));
            item.setCategory(activityCategoryFor(value));
            item.setScore(score);
            out.add(item);
        }
        return out;
    }

    /** For activity rows, several values mean "no forum ranking applies" (keynotes, committees,
     *  generic-count indicators, etc.). Leave the cell blank rather than emitting noise. */
    private String activityCategoryFor(Object scoreObj) {
        String raw = extractCoreRankingEquivalent(scoreObj);
        if (raw == null || "NON_RANK".equals(raw)
                || "Generic Activity".equals(raw) || "Generic Count".equals(raw)) {
            return null;
        }
        return CategoryLetterMapper.toTemplateLetter(raw);
    }

    private String resolveDescription(Object scoreObj, Object activitiesObj, String activityId) {
        // Prefer a rich description built from the ActivityInstance (name + user-filled fields +
        // referenceFields + year). Falls back to the activity's display name, then to the
        // Score.details audit string, then to the raw key as a last resort.
        String built = buildActivityInstanceDescription(activitiesObj, activityId);
        if (built != null && !built.isBlank()) return built;
        String label = resolveActivityLabel(activitiesObj, activityId);
        if (label != null && !label.isBlank()) return label;
        String details = extractDetails(scoreObj);
        return details != null && !details.isBlank() ? details : activityId;
    }

    @SuppressWarnings("unchecked")
    private String buildActivityInstanceDescription(Object activitiesObj, String activityId) {
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
                if (v != null && !v.toString().isBlank()) {
                    parts.add(v.toString());
                }
            }
        }

        Object date = attrs.get("date");
        if (date != null && !date.toString().isBlank()) parts.add("(" + date + ")");

        return parts.isEmpty() ? null : String.join(" — ", parts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findActivityInstanceMap(Object activitiesObj, String activityId) {
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

    private double extractAuthorScore(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Map<?, ?> m) {
            Object v = m.get("authorScore");
            return v instanceof Number n ? n.doubleValue() : 0.0;
        }
        try { return (double) obj.getClass().getMethod("getAuthorScore").invoke(obj); }
        catch (Exception e) { return 0.0; }
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
        } catch (Exception e) { return null; }
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
        } catch (Exception e) { return null; }
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
                }
            }
        }
        return null;
    }
}
