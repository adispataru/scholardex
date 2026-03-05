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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CoreRankingQueryService {

    private static final int MAX_QUERY_LENGTH = 100;

    private final MongoTemplate mongoTemplate;

    public CoreRankingPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        Query query = new Query().with(PageRequest.of(page, size, Sort.by(normalizedDirection, normalizedSort)));
        if (normalizedQuery != null) {
            String pattern = ".*" + Pattern.quote(normalizedQuery) + ".*";
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("name").regex(pattern, "i"),
                    Criteria.where("acronym").regex(pattern, "i"),
                    Criteria.where("sourceId").regex(pattern, "i")
            ));
        }

        List<CoreConferenceRanking> rows = mongoTemplate.find(query, CoreConferenceRanking.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), CoreConferenceRanking.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<CoreRankingListItemResponse> items = rows.stream().map(this::toListItem).toList();
        return new CoreRankingPageResponse(items, page, size, totalItems, totalPages);
    }

    private CoreRankingListItemResponse toListItem(CoreConferenceRanking ranking) {
        String category2023 = null;
        Map<Integer, CoreConferenceRanking.YearlyRanking> yearlyRankings = ranking.getYearlyRankings();
        if (yearlyRankings != null && yearlyRankings.get(2023) != null && yearlyRankings.get(2023).getRank() != null) {
            category2023 = yearlyRankings.get(2023).getRank().name();
        }
        return new CoreRankingListItemResponse(ranking.getId(), ranking.getName(), ranking.getAcronym(), category2023);
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        if (!normalized.equals("name") && !normalized.equals("acronym")) {
            throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, acronym.");
        }
        return normalized;
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
