package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.CoreRankingListItemResponse;
import ro.uvt.pokedex.core.controller.dto.CoreRankingPageResponse;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CoreRankingQueryService {

    private final MongoTemplate mongoTemplate;

    public CoreRankingPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        Criteria criteria = null;
        if (normalizedQuery != null) {
            String pattern = ".*" + Pattern.quote(normalizedQuery) + ".*";
            criteria = new Criteria().orOperator(
                    Criteria.where("name").regex(pattern, "i"),
                    Criteria.where("acronym").regex(pattern, "i"),
                    Criteria.where("sourceId").regex(pattern, "i")
            );
        }

        if (COMPUTED_SORTS.contains(normalizedSort)) {
            return searchByComputedSort(page, size, normalizedSort, normalizedDirection, criteria);
        }

        Query query = new Query().with(PageRequest.of(page, size, Sort.by(normalizedDirection, normalizedSort)));
        if (criteria != null) {
            query.addCriteria(criteria);
        }

        List<CoreConferenceRanking> rows = mongoTemplate.find(query, CoreConferenceRanking.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), CoreConferenceRanking.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<CoreRankingListItemResponse> items = rows.stream().map(this::toListItem).toList();
        return new CoreRankingPageResponse(items, page, size, totalItems, totalPages);
    }

    /** Sort keys derived from the yearlyRankings map, not stored fields — Mongo can't sort on them.
     *  The collection is small (~2.3k docs), so these load the filtered set and sort/page in memory. */
    private static final java.util.Set<String> COMPUTED_SORTS = java.util.Set.of("rank", "year");

    private CoreRankingPageResponse searchByComputedSort(
            int page, int size, String sort, Sort.Direction direction, Criteria criteria) {
        Query query = criteria == null ? new Query() : new Query(criteria);
        List<CoreRankingListItemResponse> all = mongoTemplate.find(query, CoreConferenceRanking.class).stream()
                .map(this::toListItem)
                .toList();

        // rank sorts by the latest edition's tier (higher = better tier), year by the latest edition;
        // conferences without the value stay at the bottom in BOTH directions.
        boolean desc = direction == Sort.Direction.DESC;
        java.util.Comparator<Integer> valueOrder = desc
                ? java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())
                : java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder());
        java.util.Comparator<CoreRankingListItemResponse> byKey = "rank".equals(sort)
                ? java.util.Comparator.comparing(CoreRankingQueryService::latestTier, valueOrder)
                : java.util.Comparator.comparing(CoreRankingListItemResponse::latestYear, valueOrder);
        List<CoreRankingListItemResponse> sorted = all.stream()
                .sorted(byKey.thenComparing(CoreRankingListItemResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        long totalItems = sorted.size();
        int totalPages = (int) Math.ceil(totalItems / (double) size);
        int from = Math.min(page * size, sorted.size());
        int to = Math.min(from + size, sorted.size());
        return new CoreRankingPageResponse(sorted.subList(from, to), page, size, totalItems, totalPages);
    }

    private static Integer latestTier(CoreRankingListItemResponse item) {
        return item.trend() == null || item.trend().isEmpty() ? null : item.trend().getLast().value();
    }

    /** CORE editions are irregular (2008, 2010, 2013 ... 2026); the trend shows the last few editions. */
    private static final int TREND_EDITIONS = 6;

    private CoreRankingListItemResponse toListItem(CoreConferenceRanking ranking) {
        Map<Integer, CoreConferenceRanking.YearlyRanking> byYear = ranking.sortedYearlyRankings();
        List<CoreRankingListItemResponse.TrendPoint> trend = byYear.entrySet().stream()
                .filter(e -> e.getValue().getRank() != null)
                .map(e -> new CoreRankingListItemResponse.TrendPoint(
                        e.getKey(),
                        e.getValue().getRank().tierValue(),
                        e.getValue().getRank().displayLabel()))
                .toList();
        if (trend.size() > TREND_EDITIONS) {
            trend = trend.subList(trend.size() - TREND_EDITIONS, trend.size());
        }
        CoreRankingListItemResponse.TrendPoint latest = trend.isEmpty() ? null : trend.getLast();
        return new CoreRankingListItemResponse(
                ranking.getId(),
                ranking.getName(),
                ranking.getAcronym(),
                latest == null ? null : latest.label(),
                latest == null ? null : latest.year(),
                trend);
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        if (!normalized.equals("name") && !normalized.equals("acronym") && !COMPUTED_SORTS.contains(normalized)) {
            throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, acronym, rank, year.");
        }
        return normalized;
    }

    private Sort.Direction normalizeDirection(String direction) {
        return Sort.Direction.fromString(QueryNormalizationSupport.normalizeDirection(direction));
    }

    private String normalizeQuery(String q) {
        return QueryNormalizationSupport.normalizeQuery(q);
    }
}
