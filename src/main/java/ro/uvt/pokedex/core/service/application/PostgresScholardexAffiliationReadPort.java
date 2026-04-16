package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScholardexAffiliationListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexAffiliationPageResponse;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PostgresScholardexAffiliationReadPort implements ScholardexAffiliationReadPort {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Override
    public ScholardexAffiliationPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (normalizedQuery != null) {
            whereClause.append(" AND (name ILIKE :qPattern ESCAPE '\\' OR id ILIKE :qPattern ESCAPE '\\' OR city ILIKE :qPattern ESCAPE '\\' OR country ILIKE :qPattern ESCAPE '\\')");
            params.addValue("qPattern", "%" + escapeLikePattern(normalizedQuery) + "%");
        }
        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        String sql = """
                SELECT id, name, city, country
                FROM reporting_read.scholardex_affiliation_view
                """ + whereClause + " ORDER BY " + normalizedSort + " " + normalizedDirection
                + ", id COLLATE \"C\" " + normalizedDirection + " LIMIT :limit OFFSET :offset";

        List<ScholardexAffiliationListItemResponse> items = namedParameterJdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new ScholardexAffiliationListItemResponse(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("city"),
                        rs.getString("country")
                )
        );

        Long totalItems = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reporting_read.scholardex_affiliation_view" + whereClause,
                params,
                Long.class
        );
        long total = totalItems == null ? 0L : totalItems;
        int totalPages = (int) Math.ceil(total / (double) size);

        return new ScholardexAffiliationPageResponse(items, page, size, total, totalPages);
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        return switch (normalized) {
            case "name" -> "name COLLATE \"C\"";
            case "afid" -> "id COLLATE \"C\"";
            case "city" -> "city COLLATE \"C\"";
            case "country" -> "country COLLATE \"C\"";
            default -> throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, afid, city, country.");
        };
    }

    private String normalizeDirection(String direction) {
        return QueryNormalizationSupport.normalizeDirection(direction).toUpperCase(java.util.Locale.ROOT);
    }

    private String normalizeQuery(String q) {
        return QueryNormalizationSupport.normalizeQuery(q);
    }

    private String escapeLikePattern(String value) {
        return QueryNormalizationSupport.escapeLikePattern(value);
    }
}
