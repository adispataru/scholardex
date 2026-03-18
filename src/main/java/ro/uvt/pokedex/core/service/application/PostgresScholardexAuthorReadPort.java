package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url")
public class PostgresScholardexAuthorReadPort implements ScholardexAuthorReadPort {

    private static final int MAX_QUERY_LENGTH = 100;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Override
    public ScopusAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);
        String normalizedAfid = normalizeAfid(afid);

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (normalizedAfid != null) {
            // Use array containment so Postgres can leverage the GIN index on affiliation_ids.
            whereClause.append(" AND a.affiliation_ids @> ARRAY[:afid]::text[]");
            params.addValue("afid", normalizedAfid);
        }
        if (normalizedQuery != null) {
            whereClause.append(" AND (a.name ILIKE :qPattern ESCAPE '\\' OR a.id ILIKE :qPattern ESCAPE '\\')");
            params.addValue("qPattern", "%" + escapeLikePattern(normalizedQuery) + "%");
        }

        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        String sql = """
                SELECT a.id, a.name, a.affiliation_ids
                FROM reporting_read.scholardex_author_view a
                """ + whereClause + " ORDER BY " + normalizedSort + " " + normalizedDirection
                + ", a.id COLLATE \"C\" " + normalizedDirection + " LIMIT :limit OFFSET :offset";

        List<AuthorRow> rows = namedParameterJdbcTemplate.query(sql, params, this::mapAuthorRow);

        Long totalItems = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reporting_read.scholardex_author_view a" + whereClause,
                params,
                Long.class
        );
        long total = totalItems == null ? 0L : totalItems;
        int totalPages = (int) Math.ceil(total / (double) size);

        List<String> affiliationIds = rows.stream()
                .flatMap(row -> row.affiliationIds().stream())
                .distinct()
                .toList();

        Map<String, String> affiliationNamesById = new LinkedHashMap<>();
        if (!affiliationIds.isEmpty()) {
            List<Map.Entry<String, String>> entries = namedParameterJdbcTemplate.query(
                    "SELECT id, name FROM reporting_read.scholardex_affiliation_view WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", affiliationIds),
                    (rs, rowNum) -> Map.entry(rs.getString("id"), rs.getString("name"))
            );
            entries.forEach(entry -> affiliationNamesById.putIfAbsent(entry.getKey(), entry.getValue()));
        }

        List<ScopusAuthorListItemResponse> items = rows.stream()
                .map(row -> new ScopusAuthorListItemResponse(
                        row.id(),
                        row.name(),
                        row.affiliationIds().stream()
                                .map(affiliationNamesById::get)
                                .filter(name -> name != null && !name.isBlank())
                                .toList()
                ))
                .toList();

        return new ScopusAuthorPageResponse(items, page, size, total, totalPages);
    }

    private AuthorRow mapAuthorRow(ResultSet rs, int ignored) throws SQLException {
        return new AuthorRow(
                rs.getString("id"),
                rs.getString("name"),
                toStringList(rs.getArray("affiliation_ids"))
        );
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
            case "name" -> "a.name COLLATE \"C\"";
            case "id" -> "a.id COLLATE \"C\"";
            default -> throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, id.");
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

    private String normalizeAfid(String afid) {
        if (afid == null) {
            return null;
        }
        String normalized = afid.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private record AuthorRow(String id, String name, List<String> affiliationIds) {
    }
}
