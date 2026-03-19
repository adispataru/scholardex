package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusForumListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MongoScholardexForumReadPort implements ScholardexForumReadPort {

    private final MongoTemplate mongoTemplate;

    @Override
    public ScopusForumPageResponse search(int page, int size, String sort, String direction, String q) {
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
                    Criteria.where("publicationName").regex(pattern, "i"),
                    Criteria.where("issn").regex(pattern, "i"),
                    Criteria.where("eIssn").regex(pattern, "i"),
                    Criteria.where("aggregationType").regex(pattern, "i")
            ));
        }

        List<ScholardexForumView> rows = mongoTemplate.find(query, ScholardexForumView.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ScholardexForumView.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<ScopusForumListItemResponse> items = rows.stream()
                .map(this::toListItem)
                .toList();
        return new ScopusForumPageResponse(items, page, size, totalItems, totalPages);
    }

    private ScopusForumListItemResponse toListItem(ScholardexForumView forum) {
        return new ScopusForumListItemResponse(
                forum.getId(),
                forum.getPublicationName(),
                forum.getIssn(),
                forum.getEIssn(),
                forum.getAggregationType()
        );
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        if (!normalized.equals("publicationName")
                && !normalized.equals("issn")
                && !normalized.equals("eIssn")
                && !normalized.equals("aggregationType")) {
            throw new IllegalArgumentException("Invalid sort parameter. Allowed: publicationName, issn, eIssn, aggregationType.");
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
