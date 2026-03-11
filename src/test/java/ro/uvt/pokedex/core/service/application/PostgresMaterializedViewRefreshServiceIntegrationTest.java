package ro.uvt.pokedex.core.service.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
class PostgresMaterializedViewRefreshServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("core_h22_5")
            .withUsername("core")
            .withPassword("core");

    private JdbcTemplate jdbcTemplate;
    private JdbcPostgresMaterializedViewRefreshService service;

    @BeforeEach
    void setup() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("reporting_read")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbcTemplate = new JdbcTemplate(dataSource);
        service = new JdbcPostgresMaterializedViewRefreshService(jdbcTemplate);
    }

    @Test
    void refreshAllManualRefreshesBothMaterializedViews() {
        seedReadCoreRows();

        PostgresMaterializedViewRefreshService.MaterializedViewRefreshRunSummary run = service.refreshAllManual();

        assertEquals("SUCCESS", run.status());
        assertEquals(2, run.views().size());
        assertEquals(1L, tableCount("reporting_read.mv_wos_top_rankings_q1_ais"));
        assertEquals(1L, tableCount("reporting_read.mv_scholardex_citation_context"));
        assertNotNull(service.latestStatus().latestRun());
    }

    @Test
    void refreshManualForWosSliceOnlyRefreshesWosMaterializedView() {
        seedReadCoreRows();

        PostgresMaterializedViewRefreshService.MaterializedViewRefreshRunSummary run =
                service.refreshManualForSlices(java.util.Set.of("wos"));

        assertEquals("SUCCESS", run.status());
        assertEquals(1, run.views().size());
        assertEquals("reporting_read.mv_wos_top_rankings_q1_ais", run.views().getFirst().viewName());
    }

    private void seedReadCoreRows() {
        jdbcTemplate.update("""
                INSERT INTO reporting_read.wos_ranking_view (journal_id, name)
                VALUES ('jr1', 'Journal One')
                ON CONFLICT (journal_id) DO NOTHING
                """);

        jdbcTemplate.update("""
                INSERT INTO reporting_read.wos_scoring_view
                (id, journal_id, year, category_name_canonical, edition_normalized, metric_type, quarter, value)
                VALUES ('sv1', 'jr1', 2025, 'COMPUTER SCIENCE', 'SCIE', 'AIS', 'Q1', 2.5)
                ON CONFLICT (id) DO NOTHING
                """);

        jdbcTemplate.update("""
                INSERT INTO reporting_read.scholardex_publication_view
                (id, eid, title, cover_date, forum_id, author_ids)
                VALUES ('p1', 'EID-1', 'Publication One', '2025-01-01', 'f1', ARRAY['a1']),
                       ('p2', 'EID-2', 'Publication Two', '2025-02-01', 'f2', ARRAY['a2'])
                ON CONFLICT (id) DO NOTHING
                """);

        jdbcTemplate.update("""
                INSERT INTO reporting_read.scholardex_citation_fact
                (id, cited_publication_id, citing_publication_id, source)
                VALUES ('c1', 'p1', 'p2', 'SCOPUS')
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private long tableCount(String tableName) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count == null ? 0L : count;
    }
}
