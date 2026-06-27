package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectPageResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * H64 slice 2 — read the canonical project view ({@code reporting_read.scholardex_project_view}) for the workspace
 * picker. Mirrors {@link PostgresScholardexAffiliationReadPort}: validated sort, ILIKE search (over title/code/funder/
 * eu_grant_id/coordinator_name), escaped patterns, deterministic id tiebreak.
 */
@Service
@RequiredArgsConstructor
public class PostgresScholardexProjectReadPort implements ScholardexProjectReadPort {

    private static final String SELECT_COLS = """
            SELECT id, code, eu_grant_id, title, funder,
                   NULLIF(TRIM(CONCAT_WS(' ', director_first, director_last)), '') AS director,
                   start_year, end_year, coordinator_name
            FROM reporting_read.scholardex_project_view
            """;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Override
    public ScholardexProjectPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = QueryNormalizationSupport.normalizeDirectionUpper(direction);
        String normalizedQuery = QueryNormalizationSupport.normalizeQuery(q);

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (normalizedQuery != null) {
            whereClause.append(" AND (title ILIKE :qPattern ESCAPE '\\' OR code ILIKE :qPattern ESCAPE '\\'"
                    + " OR funder ILIKE :qPattern ESCAPE '\\' OR eu_grant_id ILIKE :qPattern ESCAPE '\\'"
                    + " OR coordinator_name ILIKE :qPattern ESCAPE '\\')");
            params.addValue("qPattern", "%" + QueryNormalizationSupport.escapeLikePattern(normalizedQuery) + "%");
        }
        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        String sql = SELECT_COLS + whereClause + " ORDER BY " + normalizedSort + " " + normalizedDirection
                + ", id COLLATE \"C\" " + normalizedDirection + " LIMIT :limit OFFSET :offset";

        List<ScholardexProjectListItemResponse> items =
                namedParameterJdbcTemplate.query(sql, params, PostgresScholardexProjectReadPort::mapRow);

        Long totalItems = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reporting_read.scholardex_project_view" + whereClause, params, Long.class);
        long total = totalItems == null ? 0L : totalItems;
        int totalPages = (int) Math.ceil(total / (double) size);

        return new ScholardexProjectPageResponse(items, page, size, total, totalPages);
    }

    @Override
    public ScholardexProjectListItemResponse findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("id", id.trim());
        List<ScholardexProjectListItemResponse> rows = namedParameterJdbcTemplate.query(
                SELECT_COLS + " WHERE id = :id", params, PostgresScholardexProjectReadPort::mapRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static ScholardexProjectListItemResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ScholardexProjectListItemResponse(
                rs.getString("id"),
                rs.getString("code"),
                rs.getString("eu_grant_id"),
                rs.getString("title"),
                rs.getString("funder"),
                rs.getString("director"),
                getNullableInt(rs, "start_year"),
                getNullableInt(rs, "end_year"),
                rs.getString("coordinator_name"));
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        return switch (normalized) {
            case "title" -> "title COLLATE \"C\"";
            case "code" -> "code COLLATE \"C\"";
            case "funder" -> "funder COLLATE \"C\"";
            case "startYear", "start_year" -> "start_year";
            default -> throw new IllegalArgumentException(
                    "Invalid sort parameter. Allowed: title, code, funder, startYear.");
        };
    }
}
