package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationSearchView;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MongoScopusAffiliationReadPort implements ScopusAffiliationReadPort {

    private static final int MAX_QUERY_LENGTH = 100;

    private final MongoTemplate mongoTemplate;

    @Override
    public ScopusAffiliationPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        Query query = new Query().with(PageRequest.of(page, size, Sort.by(normalizedDirection, normalizedSort)));
        if (normalizedQuery != null) {
            String pattern = ".*" + Pattern.quote(normalizedQuery) + ".*";
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("name").regex(pattern, "i"),
                    Criteria.where("_id").regex(pattern, "i"),
                    Criteria.where("city").regex(pattern, "i"),
                    Criteria.where("country").regex(pattern, "i")
            ));
        }

        List<ScopusAffiliationSearchView> rows = mongoTemplate.find(query, ScopusAffiliationSearchView.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ScopusAffiliationSearchView.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<ScopusAffiliationListItemResponse> items = rows.stream()
                .map(this::toListItem)
                .toList();
        return new ScopusAffiliationPageResponse(items, page, size, totalItems, totalPages);
    }

    private ScopusAffiliationListItemResponse toListItem(ScopusAffiliationSearchView affiliation) {
        return new ScopusAffiliationListItemResponse(
                affiliation.getId(),
                affiliation.getName(),
                affiliation.getCity(),
                affiliation.getCountry()
        );
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        if (normalized.equals("name")) {
            return "name";
        }
        if (normalized.equals("afid")) {
            return "_id";
        }
        if (normalized.equals("city")) {
            return "city";
        }
        if (normalized.equals("country")) {
            return "country";
        }
        throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, afid, city, country.");
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
