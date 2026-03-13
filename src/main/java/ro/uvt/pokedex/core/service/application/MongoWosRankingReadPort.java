package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.WosRankingListItemResponse;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MongoWosRankingReadPort implements WosRankingReadPort {

    private static final int MAX_QUERY_LENGTH = 100;

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
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim()
                .toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "");
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        if (!normalized.equals("name") && !normalized.equals("issn") && !normalized.equals("eIssn")) {
            throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, issn, eIssn.");
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
