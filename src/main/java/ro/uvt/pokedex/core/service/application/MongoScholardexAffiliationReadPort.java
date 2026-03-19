package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;

import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MongoScholardexAffiliationReadPort implements ScholardexAffiliationReadPort {

    private final MongoTemplate mongoTemplate;

    @Override
    public ScopusAffiliationPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        Query query = new Query().with(PageRequest.of(
                page,
                size,
                Sort.by(
                        new Order(normalizedDirection, normalizedSort),
                        new Order(normalizedDirection, "_id")
                )
        ));
        if (normalizedQuery != null) {
            String pattern = ".*" + Pattern.quote(normalizedQuery) + ".*";
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("name").regex(pattern, "i"),
                    Criteria.where("_id").regex(pattern, "i"),
                    Criteria.where("city").regex(pattern, "i"),
                    Criteria.where("country").regex(pattern, "i")
            ));
        }

        List<ScholardexAffiliationView> rows = mongoTemplate.find(query, ScholardexAffiliationView.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ScholardexAffiliationView.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<ScopusAffiliationListItemResponse> items = rows.stream()
                .map(this::toListItem)
                .toList();
        return new ScopusAffiliationPageResponse(items, page, size, totalItems, totalPages);
    }

    private ScopusAffiliationListItemResponse toListItem(ScholardexAffiliationView affiliation) {
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
        return Sort.Direction.fromString(QueryNormalizationSupport.normalizeDirection(direction));
    }

    private String normalizeQuery(String q) {
        return QueryNormalizationSupport.normalizeQuery(q);
    }
}
