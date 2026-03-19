package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationViewRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MongoScholardexAuthorReadPort implements ScholardexAuthorReadPort {

    private final MongoTemplate mongoTemplate;
    private final ScholardexAffiliationViewRepository affiliationViewRepository;

    @Override
    public ScopusAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);
        String normalizedAfid = normalizeAfid(afid);

        Query query = new Query().with(PageRequest.of(
                page,
                size,
                Sort.by(
                        new Order(normalizedDirection, normalizedSort),
                        new Order(normalizedDirection, "_id")
                )
        ));

        List<Criteria> andCriteria = new ArrayList<>();
        if (normalizedAfid != null) {
            andCriteria.add(Criteria.where("affiliationIds").is(normalizedAfid));
        }
        if (normalizedQuery != null) {
            String pattern = ".*" + Pattern.quote(normalizedQuery) + ".*";
            andCriteria.add(new Criteria().orOperator(
                    Criteria.where("name").regex(pattern, "i"),
                    Criteria.where("_id").regex(pattern, "i")
            ));
        }
        if (!andCriteria.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(andCriteria.toArray(new Criteria[0])));
        }

        List<ScholardexAuthorView> rows = mongoTemplate.find(query, ScholardexAuthorView.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ScholardexAuthorView.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<String> affiliationIds = rows.stream()
                .flatMap(author -> author.getAffiliationIds().stream())
                .distinct()
                .toList();
        List<ScholardexAffiliationView> affiliations = affiliationIds.isEmpty()
                ? List.of()
                : affiliationViewRepository.findByIdIn(affiliationIds);
        java.util.Map<String, String> affiliationNameById = affiliations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ScholardexAffiliationView::getId,
                        ScholardexAffiliationView::getName,
                        (left, right) -> left
                ));

        List<ScopusAuthorListItemResponse> items = rows.stream()
                .map(author -> toListItem(author, affiliationNameById))
                .toList();
        return new ScopusAuthorPageResponse(items, page, size, totalItems, totalPages);
    }

    private ScopusAuthorListItemResponse toListItem(ScholardexAuthorView author, java.util.Map<String, String> affiliationNameById) {
        List<String> affiliationNames = author.getAffiliationIds() == null
                ? List.of()
                : author.getAffiliationIds().stream()
                .map(affiliationNameById::get)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        return new ScopusAuthorListItemResponse(author.getId(), author.getName(), affiliationNames);
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        if (normalized.equals("name")) {
            return "name";
        }
        if (normalized.equals("id")) {
            return "_id";
        }
        throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, id.");
    }

    private Sort.Direction normalizeDirection(String direction) {
        return Sort.Direction.fromString(QueryNormalizationSupport.normalizeDirection(direction));
    }

    private String normalizeQuery(String q) {
        return QueryNormalizationSupport.normalizeQuery(q);
    }

    private String normalizeAfid(String afid) {
        return QueryNormalizationSupport.normalizeAfid(afid);
    }
}
