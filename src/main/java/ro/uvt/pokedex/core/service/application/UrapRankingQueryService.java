package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.UrapRankingListItemResponse;
import ro.uvt.pokedex.core.controller.dto.UrapRankingPageResponse;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UrapRankingQueryService {

    private final MongoTemplate mongoTemplate;

    public UrapRankingPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        Criteria criteria = null;
        if (normalizedQuery != null) {
            String pattern = ".*" + Pattern.quote(normalizedQuery) + ".*";
            criteria = new Criteria().orOperator(
                    Criteria.where("_id").regex(pattern, "i"),
                    Criteria.where("country").regex(pattern, "i")
            );
        }

        if (COMPUTED_SORTS.contains(normalizedSort)) {
            return searchByComputedSort(page, size, normalizedSort, normalizedDirection, criteria);
        }

        Query query = new Query().with(PageRequest.of(page, size, Sort.by(normalizedDirection, normalizedSort)));
        if (criteria != null) {
            query.addCriteria(criteria);
        }

        List<URAPUniversityRanking> rows = mongoTemplate.find(query, URAPUniversityRanking.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), URAPUniversityRanking.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<UrapRankingListItemResponse> items = rows.stream().map(this::toListItem).toList();
        return new UrapRankingPageResponse(items, page, size, totalItems, totalPages);
    }

    /** Sort keys computed from the latest edition of the scores map — not stored fields, so Mongo can't
     *  sort on them. The collection is small (~3.6k docs); load the filtered set, sort/page in memory. */
    private static final Set<String> COMPUTED_SORTS = Set.of("rank", "year", "total");

    private UrapRankingPageResponse searchByComputedSort(
            int page, int size, String sort, Sort.Direction direction, Criteria criteria) {
        Query query = criteria == null ? new Query() : new Query(criteria);
        List<UrapRankingListItemResponse> all = mongoTemplate.find(query, URAPUniversityRanking.class).stream()
                .map(this::toListItem)
                .toList();

        Comparator<UrapRankingListItemResponse> byKey = computedComparator(sort, direction);
        List<UrapRankingListItemResponse> sorted = all.stream()
                .sorted(byKey.thenComparing(UrapRankingListItemResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        long totalItems = sorted.size();
        int totalPages = (int) Math.ceil(totalItems / (double) size);
        int from = Math.min(page * size, sorted.size());
        int to = Math.min(from + size, sorted.size());
        return new UrapRankingPageResponse(sorted.subList(from, to), page, size, totalItems, totalPages);
    }

    /** Universities without a value for the key stay at the bottom in BOTH directions. */
    private static Comparator<UrapRankingListItemResponse> computedComparator(String sort, Sort.Direction direction) {
        boolean desc = direction == Sort.Direction.DESC;
        return switch (sort) {
            case "rank" -> Comparator.comparing(UrapRankingListItemResponse::rank,
                    desc ? Comparator.nullsLast(Comparator.reverseOrder()) : Comparator.nullsLast(Comparator.naturalOrder()));
            case "total" -> Comparator.comparing(UrapRankingListItemResponse::total,
                    desc ? Comparator.nullsLast(Comparator.reverseOrder()) : Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(UrapRankingListItemResponse::year,
                    desc ? Comparator.nullsLast(Comparator.reverseOrder()) : Comparator.nullsLast(Comparator.naturalOrder()));
        };
    }

    /** The trend sparkline covers the last URAP editions (yearly, unlike CORE's irregular ones). */
    private static final int TREND_YEARS = 6;

    private UrapRankingListItemResponse toListItem(URAPUniversityRanking ranking) {
        Map<Integer, URAPUniversityRanking.Score> scores =
                ranking.getScores() == null ? Map.of() : ranking.getScores();
        List<UrapRankingListItemResponse.TrendPoint> trend = scores.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new UrapRankingListItemResponse.TrendPoint(
                        e.getKey(),
                        e.getValue().getRank() > 0 ? e.getValue().getRank() : null,
                        e.getValue().getTotal()))
                .toList();
        if (trend.size() > TREND_YEARS) {
            trend = trend.subList(trend.size() - TREND_YEARS, trend.size());
        }
        UrapRankingListItemResponse.TrendPoint latest = trend.isEmpty() ? null : trend.getLast();

        return new UrapRankingListItemResponse(
                ranking.getName(),
                ranking.getName(),
                ranking.getCountry(),
                latest == null ? null : latest.year(),
                latest == null ? null : latest.rank(),
                latest == null ? null : latest.total(),
                trend
        );
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        if (COMPUTED_SORTS.contains(normalized)) {
            return normalized;
        }
        if (!normalized.equals("name") && !normalized.equals("country")) {
            throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, country, rank, year, total.");
        }
        return normalized.equals("name") ? "_id" : "country";
    }

    private Sort.Direction normalizeDirection(String direction) {
        return Sort.Direction.fromString(QueryNormalizationSupport.normalizeDirection(direction));
    }

    private String normalizeQuery(String q) {
        return QueryNormalizationSupport.normalizeQuery(q);
    }
}
