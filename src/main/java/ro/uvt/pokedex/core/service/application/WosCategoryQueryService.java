package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ArrayOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.WosCategoryListItemResponse;
import ro.uvt.pokedex.core.controller.dto.WosCategoryPageResponse;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class WosCategoryQueryService {

    private static final int MAX_QUERY_LENGTH = 100;
    private static final Set<EditionNormalized> SUPPORTED_EDITIONS = Set.of(EditionNormalized.SCIE, EditionNormalized.SSCI);
    private static final String COLLECTION_NAME = "wos.category_facts";

    private final MongoTemplate mongoTemplate;

    public WosCategoryPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);
        long totalItems = fetchTotalItems(normalizedQuery);
        int totalPages = (int) Math.ceil(totalItems / (double) size);
        int safePage = totalPages == 0 ? 0 : Math.min(page, totalPages - 1);
        List<WosCategoryListItemResponse> items = fetchPage(safePage, size, normalizedSort, normalizedDirection, normalizedQuery);

        return new WosCategoryPageResponse(items, safePage, size, totalItems, totalPages);
    }

    private long fetchTotalItems(String normalizedQuery) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(baseCriteria(normalizedQuery)),
                Aggregation.group("categoryNameCanonical", "editionNormalized"),
                Aggregation.count().as("totalItems")
        );
        AggregationResults<CountRow> results = mongoTemplate.aggregate(aggregation, COLLECTION_NAME, CountRow.class);
        CountRow row = results.getUniqueMappedResult();
        return row == null ? 0L : row.totalItems();
    }

    private List<WosCategoryListItemResponse> fetchPage(int page, int size, String sort, Sort.Direction direction, String normalizedQuery) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(baseCriteria(normalizedQuery)),
                Aggregation.group("categoryNameCanonical", "editionNormalized")
                        .addToSet("journalId").as("journalIds")
                        .max("year").as("latestYear"),
                Aggregation.project()
                        .andExpression("concat(_id.categoryNameCanonical, ' - ', _id.editionNormalized)").as("key")
                        .and("_id.categoryNameCanonical").as("categoryName")
                        .and("_id.editionNormalized").as("edition")
                        .and(ArrayOperators.Size.lengthOfArray("journalIds")).as("journalCount")
                        .and("latestYear").as("latestYear"),
                Aggregation.sort(direction, sort, "categoryName"),
                Aggregation.skip((long) page * size),
                Aggregation.limit(size)
        );
        AggregationResults<WosCategoryListItemResponse> results =
                mongoTemplate.aggregate(aggregation, COLLECTION_NAME, WosCategoryListItemResponse.class);
        return results.getMappedResults();
    }

    private Criteria baseCriteria(String normalizedQuery) {
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("editionNormalized").in(SUPPORTED_EDITIONS),
                Criteria.where("categoryNameCanonical").ne(null),
                Criteria.where("categoryNameCanonical").ne(""),
                Criteria.where("journalId").ne(null),
                Criteria.where("journalId").ne("")
        );
        if (normalizedQuery == null) {
            return criteria;
        }
        String pattern = ".*" + Pattern.quote(normalizedQuery) + ".*";
        return new Criteria().andOperator(
                criteria,
                new Criteria().orOperator(
                        Criteria.where("categoryNameCanonical").regex(pattern, "i"),
                        Criteria.where("editionNormalized").regex(pattern, "i")
                )
        );
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        if (!normalized.equals("categoryName")
                && !normalized.equals("edition")
                && !normalized.equals("journalCount")
                && !normalized.equals("latestYear")) {
            throw new IllegalArgumentException("Invalid sort parameter. Allowed: categoryName, edition, journalCount, latestYear.");
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

    static record CountRow(long totalItems) {
    }
}
