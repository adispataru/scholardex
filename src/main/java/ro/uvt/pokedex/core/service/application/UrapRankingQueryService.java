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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UrapRankingQueryService {

    private static final int MAX_QUERY_LENGTH = 100;

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

    private UrapRankingListItemResponse toListItem(URAPUniversityRanking ranking) {
        Integer latestYear = null;
        URAPUniversityRanking.Score latestScore = null;

        Map<Integer, URAPUniversityRanking.Score> scores = ranking.getScores();
        if (scores != null && !scores.isEmpty()) {
            latestYear = scores.keySet().stream().max(Comparator.naturalOrder()).orElse(null);
            if (latestYear != null) {
                latestScore = scores.get(latestYear);
            }
        }

        return new UrapRankingListItemResponse(
                ranking.getName(),
                ranking.getName(),
                ranking.getCountry(),
                latestYear,
                latestScore == null ? null : latestScore.getRank(),
                latestScore == null ? null : latestScore.getArticle(),
                latestScore == null ? null : latestScore.getCitation(),
                latestScore == null ? null : latestScore.getTotalDocument(),
                latestScore == null ? null : latestScore.getAIT(),
                latestScore == null ? null : latestScore.getCIT(),
                latestScore == null ? null : latestScore.getCollaboration(),
                latestScore == null ? null : latestScore.getTotal()
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
        String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("asc") && !normalized.equals("desc")) {
            throw new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc.");
        }
        return Sort.Direction.fromString(normalized);
    }

    private String normalizeQuery(String q) {
        if (q == null) {
            return null;
        }
        String normalized = q.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Invalid q parameter. Maximum length is " + MAX_QUERY_LENGTH + ".");
        }
        return normalized;
    }
}
