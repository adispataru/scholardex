package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@ConditionalOnBean(DataSource.class)
public class PostgresScopusAffiliationReadPort implements ScopusAffiliationReadPort {

    private static final int MAX_QUERY_LENGTH = 100;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Override
    public ScopusAffiliationPageResponse search(int page, int size, String sort, String direction, String q) {
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = normalizeDirection(direction);
        String normalizedQuery = normalizeQuery(q);

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (normalizedQuery != null) {
            whereClause.append(" AND (name ILIKE :qPattern OR id ILIKE :qPattern OR city ILIKE :qPattern OR country ILIKE :qPattern)");
            params.addValue("qPattern", "%" + normalizedQuery + "%");
        }
        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        String sql = """
                SELECT id, name, city, country
                FROM reporting_read.scholardex_affiliation_view
                """ + whereClause + " ORDER BY " + normalizedSort + " " + normalizedDirection + " LIMIT :limit OFFSET :offset";

        List<ScopusAffiliationListItemResponse> items = namedParameterJdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new ScopusAffiliationListItemResponse(
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

        return new ScopusAffiliationPageResponse(items, page, size, total, totalPages);
    }

    private String normalizeSort(String sort) {
        String normalized = sort == null ? "" : sort.trim();
        return switch (normalized) {
            case "name" -> "name";
            case "afid" -> "id";
            case "city" -> "city";
            case "country" -> "country";
            default -> throw new IllegalArgumentException("Invalid sort parameter. Allowed: name, afid, city, country.");
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
}
