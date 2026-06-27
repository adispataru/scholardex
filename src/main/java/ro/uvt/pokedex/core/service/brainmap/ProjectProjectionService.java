package ro.uvt.pokedex.core.service.brainmap;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexProjectFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexProjectFactRepository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * H64 slice 1 — project the canonical {@code scholardex.project_facts} (Mongo) into the
 * {@code reporting_read.scholardex_project_view} read model (Postgres) that reports + the entity picker query.
 * Full-replacement on each run (mirrors the Scopus projection builder's wipe-then-batch-insert style).
 */
@Service
@RequiredArgsConstructor
public class ProjectProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectProjectionService.class);
    private static final int JDBC_BATCH_SIZE = 1000;

    private final ScholardexProjectFactRepository projectFactRepository;
    private final JdbcTemplate jdbcTemplate;

    /** Wipe + re-insert the project view from the current canonical facts. Returns the row count written. */
    public int rebuildView() {
        List<ScholardexProjectFact> facts = projectFactRepository.findAll();
        jdbcTemplate.update("DELETE FROM reporting_read.scholardex_project_view");
        String sql = """
                INSERT INTO reporting_read.scholardex_project_view (
                    id, eu_grant_id, code, title, title_normalized, plan, competition, funder,
                    director_first, director_last, director_role, coordinator_name, coordinator_affiliation_id,
                    partner_affiliation_ids, brainmap_project_ids, start_year, end_year, budget, source,
                    build_version, build_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        for (int i = 0; i < facts.size(); i += JDBC_BATCH_SIZE) {
            List<ScholardexProjectFact> chunk = facts.subList(i, Math.min(facts.size(), i + JDBC_BATCH_SIZE));
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    ScholardexProjectFact f = chunk.get(idx);
                    ps.setString(1, f.getId());
                    ps.setString(2, f.getEuGrantId());
                    ps.setString(3, f.getCode());
                    ps.setString(4, f.getTitle());
                    ps.setString(5, f.getTitleNormalized());
                    ps.setString(6, f.getPlan());
                    ps.setString(7, f.getCompetition());
                    ps.setString(8, f.getFunder());
                    ps.setString(9, f.getDirectorFirst());
                    ps.setString(10, f.getDirectorLast());
                    ps.setString(11, f.getDirectorRole());
                    ps.setString(12, f.getCoordinatorName());
                    ps.setString(13, f.getCoordinatorAffiliationId());
                    ps.setArray(14, textArray(ps.getConnection(), f.getPartnerAffiliationIds()));
                    ps.setArray(15, textArray(ps.getConnection(), f.getBrainmapProjectIds()));
                    setInteger(ps, 16, f.getStartYear());
                    setInteger(ps, 17, f.getEndYear());
                    setLong(ps, 18, f.getBudget());
                    ps.setString(19, f.getSource());
                    ps.setString(20, f.getBuilderVersion());
                    setInstant(ps, 21, f.getCreatedAt());
                    setInstant(ps, 22, f.getUpdatedAt());
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
        }
        log.info("Project projection: wrote {} rows to reporting_read.scholardex_project_view", facts.size());
        return facts.size();
    }

    private static Array textArray(Connection connection, List<String> values) throws SQLException {
        String[] entries = values == null ? new String[0] : values.toArray(String[]::new);
        return connection.createArrayOf("text", entries);
    }

    private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        ps.setTimestamp(index, value == null ? null : Timestamp.from(value));
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
