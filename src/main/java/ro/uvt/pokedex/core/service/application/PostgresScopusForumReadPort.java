package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusForumListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url")
public class PostgresScopusForumReadPort implements ScopusForumReadPort {

    private static final int MAX_QUERY_LENGTH = 100;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Override
    public ScopusForumPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (normalizedQuery != null) {
            whereClause.append(" AND (publication_name ILIKE :qPattern ESCAPE '\\' OR issn ILIKE :qPattern ESCAPE '\\' OR e_issn ILIKE :qPattern ESCAPE '\\' OR aggregation_type ILIKE :qPattern ESCAPE '\\')");
            params.addValue("qPattern", "%" + escapeLikePattern(normalizedQuery) + "%");
        }
        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        String sql = """
                SELECT id, publication_name, issn, e_issn, aggregation_type
                FROM reporting_read.scholardex_forum_view
                """ + whereClause + " ORDER BY " + normalizedSort + " " + normalizedDirection
                + ", id COLLATE \"C\" " + normalizedDirection + " LIMIT :limit OFFSET :offset";

        List<ScopusForumListItemResponse> items = namedParameterJdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new ScopusForumListItemResponse(
                        rs.getString("id"),
                        rs.getString("publication_name"),
                        rs.getString("issn"),
                        rs.getString("e_issn"),
                        rs.getString("aggregation_type")
                )
        );

        Long totalItems = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reporting_read.scholardex_forum_view" + whereClause,
                params,
                Long.class
        );
        long total = totalItems == null ? 0L : totalItems;
        int totalPages = (int) Math.ceil(total / (double) size);

        return new ScopusForumPageResponse(items, page, size, total, totalPages);
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        return switch (normalized) {
            case "publicationName" -> "publication_name COLLATE \"C\"";
            case "issn" -> "issn COLLATE \"C\"";
            case "eIssn" -> "e_issn COLLATE \"C\"";
            case "aggregationType" -> "aggregation_type COLLATE \"C\"";
            default -> throw new IllegalArgumentException("Invalid sort parameter. Allowed: publicationName, issn, eIssn, aggregationType.");
        };
    }

    private String normalizeDirection(String direction) {
        String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("asc") && !normalized.equals("desc")) {
            throw new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc.");
        }
        return normalized.toUpperCase(Locale.ROOT);
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

    private String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
