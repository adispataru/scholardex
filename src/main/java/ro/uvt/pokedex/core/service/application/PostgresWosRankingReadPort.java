package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.WosRankingListItemResponse;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url")
public class PostgresWosRankingReadPort implements WosRankingReadPort {

    private static final int MAX_QUERY_LENGTH = 100;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Override
    public WosRankingPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);
        String normalizedTextQuery = normalizeText(normalizedQuery);
        String normalizedIssnQuery = normalizeIssn(normalizedQuery);

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);

        if (normalizedTextQuery != null || normalizedIssnQuery != null) {
            whereClause.append(" AND (");
            boolean firstPredicate = true;
            if (normalizedTextQuery != null) {
                whereClause.append("name_norm LIKE :namePrefix ESCAPE '\\'");
                params.addValue("namePrefix", escapeLikePattern(normalizedTextQuery) + "%");
                firstPredicate = false;
            }
            if (normalizedIssnQuery != null) {
                if (!firstPredicate) {
                    whereClause.append(" OR ");
                }
                whereClause.append("""
                        issn_norm LIKE :issnPrefix ESCAPE '\'
                        OR e_issn_norm LIKE :issnPrefix ESCAPE '\'
                        OR EXISTS (
                            SELECT 1
                            FROM unnest(alternative_issns_norm) alt
                            WHERE alt LIKE :issnPrefix ESCAPE '\'
                        )
                        """);
                params.addValue("issnPrefix", escapeLikePattern(normalizedIssnQuery) + "%");
            }
            whereClause.append(")");
        }

        String sql = """
                SELECT journal_id, name, issn, e_issn, alternative_issns
                FROM reporting_read.wos_ranking_view
                """ + whereClause + " ORDER BY " + normalizedSort + " " + normalizedDirection
                // Internal tie-breaker only for stable paging. Public contract remains primary-sort-only.
                + ", journal_id COLLATE \"C\" " + normalizedDirection + " LIMIT :limit OFFSET :offset";

        List<WosRankingListItemResponse> items = namedParameterJdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new WosRankingListItemResponse(
                        rs.getString("journal_id"),
                        rs.getString("name"),
                        rs.getString("issn"),
                        rs.getString("e_issn"),
                        toStringList(rs.getArray("alternative_issns"))
                )
        );

        Long totalItems = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reporting_read.wos_ranking_view" + whereClause,
                params,
                Long.class
        );
        long total = totalItems == null ? 0L : totalItems;
        int totalPages = (int) Math.ceil(total / (double) size);

        return new WosRankingPageResponse(items, page, size, total, totalPages);
    }

    private List<String> toStringList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (value instanceof String[] items) {
            return List.of(items);
        }
        return List.of();
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        return switch (normalized) {
            case "name" -> "name COLLATE \"C\"";
            case "issn" -> "issn COLLATE \"C\"";
            case "eIssn" -> "e_issn COLLATE \"C\"";
            default -> throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, issn, eIssn.");
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

    private String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
