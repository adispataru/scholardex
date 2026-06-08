package ro.uvt.pokedex.core.service.reporting.transfer;

import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.reporting.transfer.projection.CategoryLetterMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RunIndicatorSnapshotProjector {

    public List<PublicationSnapshotItem> projectPublication(IndicatorApplyResultDto result, String roleKey) {
        if (result == null || result.rawGraph() == null) return List.of();
        if (!"publications".equals(String.valueOf(result.rawGraph().get("outputMode")))) return List.of();
        if (!(result.rawGraph().get("scores") instanceof Map<?, ?> scores)) return List.of();

        Map<String, Map<?, ?>> publicationsByTitle = publicationsByTitle(result.rawGraph().get("publications"));
        List<PublicationSnapshotItem> out = new ArrayList<>();
        for (Map.Entry<?, ?> entry : scores.entrySet()) {
            String title = String.valueOf(entry.getKey());
            Map<?, ?> publication = publicationsByTitle.get(title);
            PublicationSnapshotItem item = new PublicationSnapshotItem();
            item.setRoleKey(roleKey);
            item.setItemKey(firstNonBlank(asString(value(publication, "id")), title));
            item.setTitle(title);
            item.setAuthors(formatList(value(publication, "authors")));
            item.setForumName(firstNonBlank(
                    asString(value(publication, "forumName")),
                    asString(value(publication, "publicationName")),
                    asString(value(publication, "forum"))));
            item.setVolumeInfo(asString(value(publication, "volume")));
            item.setYear(extractYear(value(publication, "coverDate")));
            item.setAuthorCount(asInteger(value(publication, "authorCount")));
            String category = coreRankingEquivalent(entry.getValue());
            item.setForumCategoryLetter(CategoryLetterMapper.toPublicationTemplateLetter(category));
            item.setIsWorkshopDaNu(isWorkshopAdjusted(entry.getValue()) ? "DA" : "NU");
            double authorScore = authorScore(entry.getValue());
            item.setScore(authorScore);
            item.setAuthorScore(authorScore > 0 ? authorScore : score(entry.getValue()));
            out.add(item);
        }
        return out;
    }

    public List<CitationSnapshotItem> projectCitations(IndicatorApplyResultDto result, String roleKey) {
        if (result == null || result.rawGraph() == null) return List.of();
        if (!"citations".equals(String.valueOf(result.rawGraph().get("outputMode")))) return List.of();
        if (!(result.rawGraph().get("scores") instanceof Map<?, ?> scores)) return List.of();

        Map<String, Map<?, ?>> citedPublicationsByTitle = publicationsByTitle(result.rawGraph().get("publications"));
        Map<String, Map<?, ?>> citationMapByTitle = publicationsByTitle(result.rawGraph().get("citationMap"));
        List<CitationSnapshotItem> out = new ArrayList<>();
        for (Map.Entry<?, ?> entry : scores.entrySet()) {
            CitationSnapshotItem tile = new CitationSnapshotItem();
            String title = String.valueOf(entry.getKey());
            Map<?, ?> citedPublication = citedPublicationsByTitle.get(title);
            tile.setRoleKey(roleKey);
            tile.setItemKey(firstNonBlank(asString(value(citedPublication, "id")), title));
            tile.setPublicationTitle(title);
            tile.setPublicationForumName(firstNonBlank(
                    asString(value(citedPublication, "forumName")),
                    asString(value(citedPublication, "publicationName")),
                    asString(value(citedPublication, "forum"))));
            tile.setPublicationYear(extractYear(value(citedPublication, "coverDate")));
            tile.setPublicationAuthorCount(asInteger(value(citedPublication, "authorCount")));

            double tileScore = 0.0;
            if (entry.getValue() instanceof Map<?, ?> citingScores) {
                for (Map.Entry<?, ?> citingEntry : citingScores.entrySet()) {
                    String citingTitle = String.valueOf(citingEntry.getKey());
                    if ("total".equals(citingTitle)) continue;
                    Object scoreObj = citingEntry.getValue();
                    tileScore += authorScore(scoreObj);

                    Map<?, ?> citingPublication = citationMapByTitle.get(citingTitle);
                    CitationSnapshotItem.CitingPublication row = new CitationSnapshotItem.CitingPublication();
                    row.setTitle(citingTitle);
                    row.setAuthors(formatList(value(citingPublication, "authors")));
                    row.setForumName(firstNonBlank(
                            asString(value(citingPublication, "forumName")),
                            asString(value(citingPublication, "publicationName")),
                            asString(value(citingPublication, "forum"))));
                    row.setVolumeInfo(asString(value(citingPublication, "volume")));
                    row.setYear(extractYear(value(citingPublication, "coverDate")));
                    row.setIsWorkshopDaNu(isWorkshopAdjusted(scoreObj) ? "DA" : "NU");
                    row.setForumCategoryLetter(CategoryLetterMapper.toPublicationTemplateLetter(coreRankingEquivalent(scoreObj)));
                    row.setScore(score(scoreObj));
                    tile.getCitingPublications().add(row);
                }
            }
            if (tileScore <= 0) {
                tileScore = authorScore(entry.getValue()) > 0 ? authorScore(entry.getValue()) : score(entry.getValue());
            }
            tile.setScore(tileScore);
            out.add(tile);
        }
        return out;
    }

    public List<ActivitySnapshotItem> projectActivityBlocks(
            IndividualReport report,
            TemplateBinding binding,
            Map<String, IndicatorApplyResultDto> resultsByIndicatorId) {
        Map<String, List<Indicator>> indicatorsByBlock = indicatorsByBlock(report);
        List<ActivitySnapshotItem> out = new ArrayList<>();
        for (var role : binding.getRoles()) {
            if (role.getKind() != BindingKind.STACKED_BLOCKS) continue;
            for (var block : role.getBlocks()) {
                List<Indicator> indicators = indicatorsByBlock.getOrDefault(block.getActivityName(), List.of());
                for (Indicator indicator : indicators) {
                    IndicatorApplyResultDto result = resultsByIndicatorId.get(indicator.getId());
                    if (result == null || result.rawGraph() == null) continue;
                    if ("publications".equals(String.valueOf(result.rawGraph().get("outputMode")))) {
                        for (PublicationSnapshotItem pub : projectPublication(result, role.getRoleKey())) {
                            ActivitySnapshotItem item = new ActivitySnapshotItem();
                            item.setRoleKey(role.getRoleKey());
                            item.setActivityName(block.getActivityName());
                            item.setActivityId(indicator.getActivity() != null ? indicator.getActivity().getId() : null);
                            item.setItemKey(block.getActivityName() + ":" + firstNonBlank(pub.getItemKey(), pub.getTitle()));
                            item.setDescription(formatPublicationDescription(pub));
                            item.setCategory(pub.getForumCategoryLetter());
                            item.setScore(pub.getAuthorScore() != null ? pub.getAuthorScore() : 0.0);
                            out.add(item);
                        }
                    } else if ("activities".equals(String.valueOf(result.rawGraph().get("outputMode")))) {
                        out.addAll(projectActivities(result, role.getRoleKey(), block.getActivityName(), indicator));
                    }
                }
            }
        }
        return out;
    }

    private Map<String, List<Indicator>> indicatorsByBlock(IndividualReport report) {
        if (report.getIndicators() == null) return Map.of();
        Map<String, String> assigned = report.getBlockByIndicatorId() != null
                ? report.getBlockByIndicatorId() : Map.of();
        Map<String, List<Indicator>> out = new LinkedHashMap<>();
        for (Indicator indicator : report.getIndicators()) {
            if (indicator == null || indicator.getId() == null) continue;
            if (ReportExportReadinessValidator.isExcludedFromTemplate(report, indicator.getId())) continue;
            String blockName = assigned.get(indicator.getId());
            if ((blockName == null || blockName.isBlank())
                    && indicator.getActivity() != null && indicator.getActivity().getName() != null) {
                blockName = indicator.getActivity().getName();
            }
            if (blockName == null || blockName.isBlank()) continue;
            out.computeIfAbsent(blockName, ignored -> new ArrayList<>()).add(indicator);
        }
        return out;
    }

    private List<ActivitySnapshotItem> projectActivities(
            IndicatorApplyResultDto result,
            String roleKey,
            String blockName,
            Indicator indicator) {
        if (!(result.rawGraph().get("scores") instanceof Map<?, ?> scores)) return List.of();
        Object activitiesObj = result.rawGraph().get("activities");
        List<ActivitySnapshotItem> out = new ArrayList<>();
        for (Map.Entry<?, ?> entry : scores.entrySet()) {
            double authorScore = authorScore(entry.getValue());
            if (authorScore <= 0) continue;
            String key = String.valueOf(entry.getKey());
            ActivitySnapshotItem item = new ActivitySnapshotItem();
            item.setRoleKey(roleKey);
            item.setActivityName(blockName);
            item.setActivityId(indicator.getActivity() != null ? indicator.getActivity().getId() : null);
            item.setItemKey(blockName + ":" + key);
            item.setDescription(firstNonBlank(details(entry.getValue()), activityLabel(activitiesObj, key), key));
            String category = coreRankingEquivalent(entry.getValue());
            item.setCategory("NON_RANK".equals(category) ? null : CategoryLetterMapper.toTemplateLetter(category));
            item.setScore(authorScore);
            out.add(item);
        }
        return out;
    }

    private Map<String, Map<?, ?>> publicationsByTitle(Object publicationsObj) {
        if (publicationsObj instanceof Map<?, ?> publicationsMap) {
            Map<String, Map<?, ?>> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : publicationsMap.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> value) {
                    String title = firstNonBlank(asString(value(value, "title")), String.valueOf(entry.getKey()));
                    out.put(title, value);
                }
            }
            return out;
        }
        if (!(publicationsObj instanceof List<?> publications)) return Map.of();
        return publications.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(pub -> value(pub, "title") != null)
                .collect(Collectors.toMap(
                        pub -> String.valueOf(value(pub, "title")),
                        pub -> pub,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private String formatPublicationDescription(PublicationSnapshotItem pub) {
        List<String> parts = new ArrayList<>();
        if (pub.getTitle() != null && !pub.getTitle().isBlank()) parts.add(pub.getTitle());
        if (pub.getAuthors() != null && !pub.getAuthors().isBlank()) parts.add(pub.getAuthors());
        if (pub.getForumName() != null && !pub.getForumName().isBlank()) parts.add(pub.getForumName());
        String main = String.join(" — ", parts);
        if (pub.getVolumeInfo() != null && !pub.getVolumeInfo().isBlank()) main += ", " + pub.getVolumeInfo();
        if (pub.getYear() != null) main += " (" + pub.getYear() + ")";
        return main;
    }

    private Object value(Map<?, ?> map, String key) {
        return map != null ? map.get(key) : null;
    }

    private String formatList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return asString(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private Integer extractYear(Object value) {
        String text = asString(value);
        if (text == null || text.length() < 4) return null;
        try {
            return Integer.parseInt(text.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return null;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private double authorScore(Object scoreObj) {
        if (scoreObj instanceof ro.uvt.pokedex.core.service.reporting.Score score) {
            return score.getAuthorScore();
        }
        if (scoreObj instanceof Map<?, ?> map && map.get("authorScore") instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    private double score(Object scoreObj) {
        if (scoreObj instanceof ro.uvt.pokedex.core.service.reporting.Score score) {
            return score.getScore();
        }
        if (scoreObj instanceof Map<?, ?> map && map.get("score") instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    private String coreRankingEquivalent(Object scoreObj) {
        if (scoreObj instanceof ro.uvt.pokedex.core.service.reporting.Score score) {
            return score.getCoreRankingEquivalent();
        }
        if (scoreObj instanceof Map<?, ?> map && map.get("coreRankingEquivalent") != null) {
            return String.valueOf(map.get("coreRankingEquivalent"));
        }
        return null;
    }

    private boolean isWorkshopAdjusted(Object scoreObj) {
        Object value = null;
        if (scoreObj instanceof ro.uvt.pokedex.core.service.reporting.Score score && score.getScoringInfo() != null) {
            value = score.getScoringInfo().get("workshopAdjusted");
        } else if (scoreObj instanceof Map<?, ?> map && map.get("scoringInfo") instanceof Map<?, ?> scoringInfo) {
            value = scoringInfo.get("workshopAdjusted");
        }
        return value instanceof Boolean b ? b : value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String details(Object scoreObj) {
        if (scoreObj instanceof Map<?, ?> map && map.get("details") != null) return String.valueOf(map.get("details"));
        return null;
    }

    private String activityLabel(Object activitiesObj, String activityId) {
        if (activitiesObj instanceof Map<?, ?> activities && activities.get(activityId) instanceof Map<?, ?> attrs) {
            Object name = attrs.get("name");
            return name != null ? String.valueOf(name) : null;
        }
        if (activitiesObj instanceof List<?> activities) {
            for (Object activity : activities) {
                if (activity instanceof Map<?, ?> attrs && activityId.equals(String.valueOf(attrs.get("id")))) {
                    Object name = attrs.get("name");
                    return name != null ? String.valueOf(name) : null;
                }
            }
        }
        return null;
    }
}
