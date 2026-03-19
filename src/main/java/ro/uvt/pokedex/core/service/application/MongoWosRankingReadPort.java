package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import ro.uvt.pokedex.core.controller.dto.WosRankingListItemResponse;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class MongoWosRankingReadPort implements WosRankingReadPort {

    private final MongoTemplate mongoTemplate;

    @Override
    public WosRankingPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        Query query = new Query().with(PageRequest.of(page, size, Sort.by(normalizedDirection, normalizedSort)));
        if (normalizedQuery != null) {
            query.addCriteria(prefixSearchCriteria(normalizedQuery));
        }

        List<WosRankingView> rows = mongoTemplate.find(query, WosRankingView.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), WosRankingView.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<WosRankingListItemResponse> items = rows.stream()
                .map(this::toListItem)
                .toList();
        return new WosRankingPageResponse(items, page, size, totalItems, totalPages);
    }

    private WosRankingListItemResponse toListItem(WosRankingView view) {
        return new WosRankingListItemResponse(
                view.getId(),
                view.getName(),
                view.getIssn(),
                view.getEIssn(),
                view.getAlternativeIssns() == null ? List.of() : view.getAlternativeIssns()
        );
    }

    private Criteria prefixSearchCriteria(String rawQuery) {
        String nameQuery = normalizeText(rawQuery);
        String issnQuery = normalizeIssn(rawQuery);
        List<Criteria> criteria = new ArrayList<>();
        if (nameQuery != null) {
            criteria.add(Criteria.where("nameNorm").regex(prefixPattern(nameQuery)));
        }
        if (issnQuery != null) {
            criteria.add(Criteria.where("issnNorm").regex(prefixPattern(issnQuery)));
            criteria.add(Criteria.where("eIssnNorm").regex(prefixPattern(issnQuery)));
            criteria.add(Criteria.where("alternativeIssnsNorm").regex(prefixPattern(issnQuery)));
        }
        if (criteria.isEmpty()) {
            return new Criteria();
        }
        if (criteria.size() == 1) {
            return criteria.getFirst();
        }
        return new Criteria().orOperator(criteria.toArray(Criteria[]::new));
    }

    private String prefixPattern(String value) {
        return "^" + Pattern.quote(value);
    }

    private String normalizeText(String raw) {
        return QueryNormalizationSupport.normalizeText(raw);
    }

    private String normalizeIssn(String raw) {
        return QueryNormalizationSupport.normalizeIssn(raw);
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        if (!normalized.equals("name") && !normalized.equals("issn") && !normalized.equals("eIssn")) {
            throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, issn, eIssn.");
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
