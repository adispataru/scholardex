package ro.uvt.pokedex.core.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresReportingReadSchemaMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("core_h22")
            .withUsername("core")
            .withPassword("core");

    @BeforeEach
    void migrateFreshSchema() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("reporting_read")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void migrationsCreateExpectedTypesTablesAndIndexes() throws Exception {
        try (Connection connection = openConnection()) {
            assertTrue(objectExists(connection, "reporting_read", "edition_normalized_enum", "TYPE"));
            assertTrue(objectExists(connection, "reporting_read", "metric_type_enum", "TYPE"));
            assertTrue(objectExists(connection, "reporting_read", "wos_source_type_enum", "TYPE"));

            assertTrue(objectExists(connection, "reporting_read", "wos_ranking_view", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "wos_metric_fact", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "wos_category_fact", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "wos_scoring_view", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "scholardex_publication_view", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "scholardex_author_view", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "scholardex_forum_view", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "scholardex_affiliation_view", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "scholardex_citation_fact", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "scholardex_authorship_fact", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "scholardex_author_affiliation_fact", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "projection_checkpoint", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "projection_run", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "projection_slice_run", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "mv_refresh_run", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "mv_refresh_view_run", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "dual_read_gate_run", "TABLE"));
            assertTrue(objectExists(connection, "reporting_read", "dual_read_gate_scenario_run", "TABLE"));
            assertTrue(materializedViewExists(connection, "reporting_read", "mv_wos_top_rankings_q1_ais"));
            assertTrue(materializedViewExists(connection, "reporting_read", "mv_scholardex_citation_context"));

            assertTrue(indexExists(connection, "reporting_read", "idx_wos_scoring_lookup"));
            assertTrue(indexExists(connection, "reporting_read", "idx_wos_scoring_journal_timeline"));
            assertTrue(indexExists(connection, "reporting_read", "idx_wos_ranking_issn_norm"));
            assertTrue(indexExists(connection, "reporting_read", "idx_wos_ranking_e_issn_norm"));
            assertTrue(indexExists(connection, "reporting_read", "idx_wos_ranking_alt_issns_norm_gin"));
            assertTrue(indexExists(connection, "reporting_read", "idx_wos_category_fact_journal_edition_metric_year"));
            assertTrue(indexExists(connection, "reporting_read", "uq_scholardex_publication_eid"));
            assertTrue(indexExists(connection, "reporting_read", "idx_scholardex_publication_title_lower"));
            assertTrue(indexExists(connection, "reporting_read", "idx_scholardex_citation_cited"));
            assertTrue(indexExists(connection, "reporting_read", "idx_scholardex_authorship_author"));
            assertTrue(indexExists(connection, "reporting_read", "idx_scholardex_author_affiliation_affiliation"));
            assertTrue(indexExists(connection, "reporting_read", "idx_projection_run_started_at"));
            assertTrue(indexExists(connection, "reporting_read", "idx_projection_slice_run_run_id"));
            assertTrue(indexExists(connection, "reporting_read", "uq_mv_wos_top_rankings_q1_ais"));
            assertTrue(indexExists(connection, "reporting_read", "uq_mv_scholardex_citation_context_edge"));
            assertTrue(indexExists(connection, "reporting_read", "idx_mv_scholardex_citation_context_cited"));
            assertTrue(indexExists(connection, "reporting_read", "idx_mv_refresh_run_started_at"));
            assertTrue(indexExists(connection, "reporting_read", "idx_mv_refresh_view_run_run_id"));
            assertTrue(indexExists(connection, "reporting_read", "idx_dual_read_gate_run_started_at"));
            assertTrue(indexExists(connection, "reporting_read", "idx_dual_read_gate_scenario_run_run_id"));
        }
    }

    @Test
    void uniquenessConstraintsRejectDuplicateMetricCategoryAndEdgeRows() throws Exception {
        try (Connection connection = openConnection()) {
            execute(connection, """
                    INSERT INTO reporting_read.wos_ranking_view (journal_id, name, issn_norm)
                    VALUES ('j1', 'Journal One', '1234X');
                    """);

            execute(connection, """
                    INSERT INTO reporting_read.wos_metric_fact
                    (id, journal_id, year, metric_type, value)
                    VALUES ('m1', 'j1', 2023, 'AIS', 2.5);
                    """);
            assertUniqueViolation(connection, """
                    INSERT INTO reporting_read.wos_metric_fact
                    (id, journal_id, year, metric_type, value)
                    VALUES ('m2', 'j1', 2023, 'AIS', 3.0);
                    """);

            execute(connection, """
                    INSERT INTO reporting_read.wos_category_fact
                    (id, journal_id, year, category_name_canonical, edition_normalized, metric_type, quarter)
                    VALUES ('c1', 'j1', 2023, 'COMPUTER SCIENCE', 'SCIE', 'AIS', 'Q1');
                    """);
            assertUniqueViolation(connection, """
                    INSERT INTO reporting_read.wos_category_fact
                    (id, journal_id, year, category_name_canonical, edition_normalized, metric_type, quarter)
                    VALUES ('c2', 'j1', 2023, 'COMPUTER SCIENCE', 'SCIE', 'AIS', 'Q2');
                    """);

            execute(connection, """
                    INSERT INTO reporting_read.scholardex_publication_view (id, eid, title)
                    VALUES ('p1', 'EID-1', 'Publication One'),
                           ('p2', 'EID-2', 'Publication Two');
                    """);
            execute(connection, """
                    INSERT INTO reporting_read.scholardex_author_view (id, name)
                    VALUES ('a1', 'Author One');
                    """);
            execute(connection, """
                    INSERT INTO reporting_read.scholardex_affiliation_view (id, name)
                    VALUES ('af1', 'Affiliation One');
                    """);

            execute(connection, """
                    INSERT INTO reporting_read.scholardex_citation_fact
                    (id, cited_publication_id, citing_publication_id, source)
                    VALUES ('sci1', 'p1', 'p2', 'SCOPUS');
                    """);
            assertUniqueViolation(connection, """
                    INSERT INTO reporting_read.scholardex_citation_fact
                    (id, cited_publication_id, citing_publication_id, source)
                    VALUES ('sci2', 'p1', 'p2', 'SCOPUS');
                    """);

            execute(connection, """
                    INSERT INTO reporting_read.scholardex_authorship_fact
                    (id, publication_id, author_id, source)
                    VALUES ('au1', 'p1', 'a1', 'SCOPUS');
                    """);
            assertUniqueViolation(connection, """
                    INSERT INTO reporting_read.scholardex_authorship_fact
                    (id, publication_id, author_id, source)
                    VALUES ('au2', 'p1', 'a1', 'SCOPUS');
                    """);

            execute(connection, """
                    INSERT INTO reporting_read.scholardex_author_affiliation_fact
                    (id, author_id, affiliation_id, source)
                    VALUES ('aa1', 'a1', 'af1', 'SCOPUS');
                    """);
            assertUniqueViolation(connection, """
                    INSERT INTO reporting_read.scholardex_author_affiliation_fact
                    (id, author_id, affiliation_id, source)
                    VALUES ('aa2', 'a1', 'af1', 'SCOPUS');
                    """);
        }
    }

    @Test
    void queryShapeSmokeSupportsCurrentLookupPredicates() throws Exception {
        try (Connection connection = openConnection()) {
            execute(connection, """
                    INSERT INTO reporting_read.wos_ranking_view
                    (journal_id, name, issn_norm, e_issn_norm, alternative_issns_norm)
                    VALUES ('jr1', 'Journal R1', '1234X', '5678Y', ARRAY['9999Z']),
                           ('jr2', 'Journal R2', '8888Q', '7777W', ARRAY['1234X']);
                    """);

            execute(connection, """
                    INSERT INTO reporting_read.wos_scoring_view
                    (id, journal_id, year, category_name_canonical, edition_normalized, metric_type, quarter, value)
                    VALUES ('sv1', 'jr1', 2023, 'COMPUTER SCIENCE', 'SCIE', 'AIS', 'Q1', 2.2),
                           ('sv2', 'jr2', 2023, 'COMPUTER SCIENCE', 'SSCI', 'AIS', 'Q1', 1.8);
                    """);

            execute(connection, """
                    INSERT INTO reporting_read.scholardex_publication_view
                    (id, eid, wos_id, google_scholar_id, title, cover_date)
                    VALUES ('p1', 'EID-1', 'WOS-1', 'GS-1', 'Deep Learning', '2023-01-01'),
                           ('p2', 'EID-2', 'WOS-2', 'GS-2', 'Data Mining', '2022-01-01');
                    """);

            execute(connection, """
                    INSERT INTO reporting_read.scholardex_citation_fact
                    (id, cited_publication_id, citing_publication_id, source)
                    VALUES ('c1', 'p1', 'p2', 'SCOPUS');
                    """);

            execute(connection, "REFRESH MATERIALIZED VIEW CONCURRENTLY reporting_read.mv_wos_top_rankings_q1_ais;");
            execute(connection, "REFRESH MATERIALIZED VIEW CONCURRENTLY reporting_read.mv_scholardex_citation_context;");

            long issnMatches = queryLong(connection, """
                    SELECT COUNT(*)
                    FROM reporting_read.wos_ranking_view
                    WHERE issn_norm = '1234X'
                       OR e_issn_norm = '1234X'
                       OR alternative_issns_norm @> ARRAY['1234X'];
                    """);
            assertEquals(2L, issnMatches);

            long topRankings = queryLong(connection, """
                    SELECT COUNT(DISTINCT journal_id)
                    FROM reporting_read.wos_scoring_view
                    WHERE metric_type = 'AIS'
                      AND year = 2023
                      AND quarter = 'Q1'
                      AND category_name_canonical = 'COMPUTER SCIENCE'
                      AND edition_normalized IN ('SCIE', 'SSCI');
                    """);
            assertEquals(2L, topRankings);

            long topRankingsFromMv = queryLong(connection, """
                    SELECT COALESCE(SUM(top_journal_count), 0)
                    FROM reporting_read.mv_wos_top_rankings_q1_ais
                    WHERE year = 2023
                      AND category_name_canonical = 'COMPUTER SCIENCE'
                      AND edition_normalized IN ('SCIE', 'SSCI');
                    """);
            assertEquals(2L, topRankingsFromMv);

            long publicationByAnyId = queryLong(connection, """
                    SELECT COUNT(*)
                    FROM reporting_read.scholardex_publication_view
                    WHERE id = 'EID-1' OR eid = 'EID-1' OR wos_id = 'EID-1' OR google_scholar_id = 'EID-1';
                    """);
            assertEquals(1L, publicationByAnyId);

            long citationTraversal = queryLong(connection, """
                    SELECT COUNT(*)
                    FROM reporting_read.scholardex_citation_fact
                    WHERE cited_publication_id = 'p1';
                    """);
            assertEquals(1L, citationTraversal);

            long citationTraversalFromMv = queryLong(connection, """
                    SELECT COUNT(*)
                    FROM reporting_read.mv_scholardex_citation_context
                    WHERE cited_publication_id = 'p1';
                    """);
            assertEquals(1L, citationTraversalFromMv);
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        assertNotNull(connection);
        return connection;
    }

    private boolean objectExists(Connection connection, String schema, String objectName, String objectType) throws SQLException {
        if ("TABLE".equals(objectType)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = ?
                    """)) {
                statement.setString(1, schema);
                statement.setString(2, objectName);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1) == 1;
                }
            }
        }
        if ("TYPE".equals(objectType)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM pg_type t
                    JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ? AND t.typname = ?
                    """)) {
                statement.setString(1, schema);
                statement.setString(2, objectName);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1) == 1;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported object type: " + objectType);
    }

    private boolean indexExists(Connection connection, String schema, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = ? AND indexname = ?
                """)) {
            statement.setString(1, schema);
            statement.setString(2, indexName);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private boolean materializedViewExists(Connection connection, String schema, String viewName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM pg_matviews
                WHERE schemaname = ? AND matviewname = ?
                """)) {
            statement.setString(1, schema);
            statement.setString(2, viewName);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1) == 1;
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void assertUniqueViolation(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            throw new AssertionError("Expected unique-constraint violation");
        } catch (SQLException ex) {
            assertEquals("23505", ex.getSQLState());
        }
    }
}
