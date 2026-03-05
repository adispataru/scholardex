package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Affiliation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ScopusAuthorQueryService {

    private static final int MAX_QUERY_LENGTH = 100;

    private final MongoTemplate mongoTemplate;

    public ScopusAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        Sort.Direction normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);
        String normalizedAfid = normalizeAfid(afid);

        Query query = new Query().with(PageRequest.of(page, size, Sort.by(normalizedDirection, normalizedSort)));

        List<Criteria> andCriteria = new ArrayList<>();
        if (normalizedAfid != null) {
            andCriteria.add(Criteria.where("affiliations.$id").is(normalizedAfid));
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

        List<Author> rows = mongoTemplate.find(query, Author.class);
        long totalItems = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), Author.class);
        int totalPages = (int) Math.ceil(totalItems / (double) size);

        List<ScopusAuthorListItemResponse> items = rows.stream()
                .map(this::toListItem)
                .toList();
        return new ScopusAuthorPageResponse(items, page, size, totalItems, totalPages);
    }

    private ScopusAuthorListItemResponse toListItem(Author author) {
        List<String> affiliationNames = author.getAffiliations() == null
                ? List.of()
                : author.getAffiliations().stream()
                .map(Affiliation::getName)
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

    private String normalizeAfid(String afid) {
        if (afid == null) {
            return null;
        }
        String normalized = afid.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
