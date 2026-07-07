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

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UrapRankingQueryService {

    private final MongoTemplate mongoTemplate;

    public UrapRankingPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        Query query = new Query().with(PageRequest.of(page, size, Sort.by(normalizedDirection, normalizedSort)));
        if (normalizedQuery != null) {
            String pattern = ".*" + Pattern.quote(normalizedQuery) + ".*";
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("_id").regex(pattern, "i"),
                    Criteria.where("country").regex(pattern, "i")
            ));
        }

        List<URAPUniversityRanking> rows = mongoTemplate.find(query, URAPUniversityRanking.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), URAPUniversityRanking.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<UrapRankingListItemResponse> items = rows.stream().map(this::toListItem).toList();
        return new UrapRankingPageResponse(items, page, size, totalItems, totalPages);
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
        if (!normalized.equals("name") && !normalized.equals("country")) {
            throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, country.");
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
