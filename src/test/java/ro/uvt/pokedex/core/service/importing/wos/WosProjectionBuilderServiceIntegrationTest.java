package ro.uvt.pokedex.core.service.importing.wos;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Testcontainers
class WosProjectionBuilderServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("core_wos_proj_test")
            .withUsername("core")
            .withPassword("core");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private JdbcTemplate jdbcTemplate;
    private WosProjectionBuilderService service;
    private WosJournalIdentityRepository identityRepository;
    private WosMetricFactRepository metricFactRepository;
    private WosCategoryFactRepository categoryFactRepository;

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

        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "wos_proj_integration_test");
        mongoTemplate.getDb().drop();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);

        MongoRepositoryFactory mongoRepoFactory = new MongoRepositoryFactory(mongoTemplate);
        identityRepository = mongoRepoFactory.getRepository(WosJournalIdentityRepository.class);
        metricFactRepository = mongoRepoFactory.getRepository(WosMetricFactRepository.class);
        categoryFactRepository = mongoRepoFactory.getRepository(WosCategoryFactRepository.class);

        WosOptimizationProperties props = new WosOptimizationProperties();
        props.setPreflightIndexesEnabled(false);
        props.setProjectionWriteChunkSize(50);

        service = new WosProjectionBuilderService(
                identityRepository,
                mongoTemplate,
                Mockito.mock(WosIndexMaintenanceService.class),
                props,
                jdbcTemplate,
                txManager,
                org.mockito.Mockito.mock(ro.uvt.pokedex.core.service.application.ReportingDataEpochService.class)
        );
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void fullRebuildWritesCorrectValuesToAllFourTables() {
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-integ-1");
        identity.setTitle("  Journal of INTEGRATION  ");
        identity.setPrimaryIssn("1234-5678");
        identity.setEIssn("8765-4321");
        identity.setAliasIssns(List.of("1111-2222"));
        identity.setAlternativeNames(List.of("Alt Integration Journal"));
        identityRepository.save(identity);

        WosMetricFact metric = new WosMetricFact();
        metric.setId("mf-integ-1");
        metric.setJournalId("jid-integ-1");
        metric.setYear(2023);
        metric.setMetricType(MetricType.AIS);
        metric.setValue(1.23);
        metric.setSourceType(WosSourceType.GOV_AIS_RIS);
        metric.setSourceEventId("ev-1");
        metric.setSourceFile("ais.xlsx");
        metric.setSourceVersion("v2023");
        metric.setSourceRowItem("42");
        metric.setCreatedAt(Instant.parse("2024-01-15T10:00:00Z"));
        metricFactRepository.save(metric);

        WosCategoryFact category = new WosCategoryFact();
        category.setId("cf-integ-1");
        category.setJournalId("jid-integ-1");
        category.setYear(2023);
        category.setCategoryNameCanonical("ACOUSTICS");
        category.setEditionRaw("SCIE");
        category.setEditionNormalized(EditionNormalized.SCIE);
        category.setMetricType(MetricType.AIS);
        category.setQuarter("Q1");
        category.setQuartileRank(null);
        category.setRank(3);
        category.setSourceType(WosSourceType.GOV_AIS_RIS);
        category.setSourceEventId("ev-2");
        category.setSourceFile("ais.xlsx");
        category.setSourceVersion("v2023");
        category.setSourceRowItem("42");
        category.setCreatedAt(Instant.parse("2024-01-15T10:00:00Z"));
        categoryFactRepository.save(category);

        service.rebuildWosProjections();

        // --- wos_ranking_view ---
        Map<String, Object> ranking = jdbcTemplate.queryForMap(
                "SELECT * FROM reporting_read.wos_ranking_view WHERE journal_id = ?", "jid-integ-1");
        assertEquals("journal of integration", ranking.get("name_norm"));
        assertEquals("12345678", ranking.get("issn_norm"));
        assertEquals("87654321", ranking.get("e_issn_norm"));
        assertEquals(2023, ranking.get("latest_ais_year"));
        assertNull(ranking.get("latest_ris_year"));
        assertEquals("SCIE", ranking.get("latest_edition_normalized").toString());
        assertNotNull(ranking.get("build_version"));

        // --- wos_metric_fact ---
        Map<String, Object> metricRow = jdbcTemplate.queryForMap(
                "SELECT * FROM reporting_read.wos_metric_fact WHERE id = ?", "mf-integ-1");
        assertEquals("jid-integ-1", metricRow.get("journal_id"));
        assertEquals(2023, metricRow.get("year"));
        assertEquals("AIS", metricRow.get("metric_type").toString());
        assertEquals(1.23, (Double) metricRow.get("value"), 0.001);
        assertEquals("GOV_AIS_RIS", metricRow.get("source_type").toString());
        assertEquals("ev-1", metricRow.get("source_event_id"));
        assertEquals("ais.xlsx", metricRow.get("source_file"));
        assertEquals("v2023", metricRow.get("source_version"));
        assertEquals("42", metricRow.get("source_row_item"));
        assertNotNull(metricRow.get("created_at"));

        // --- wos_category_fact ---
        Map<String, Object> categoryRow = jdbcTemplate.queryForMap(
                "SELECT * FROM reporting_read.wos_category_fact WHERE id = ?", "cf-integ-1");
        assertEquals("jid-integ-1", categoryRow.get("journal_id"));
        assertEquals(2023, categoryRow.get("year"));
        assertEquals("ACOUSTICS", categoryRow.get("category_name_canonical"));
        assertEquals("SCIE", categoryRow.get("edition_raw"));
        assertEquals("SCIE", categoryRow.get("edition_normalized").toString());
        assertEquals("AIS", categoryRow.get("metric_type").toString());
        assertEquals("Q1", categoryRow.get("quarter"));
        assertNull(categoryRow.get("quartile_rank"));
        assertEquals(3, categoryRow.get("rank"));

        // --- wos_scoring_view ---
        Map<String, Object> scoringRow = jdbcTemplate.queryForMap(
                "SELECT * FROM reporting_read.wos_scoring_view WHERE journal_id = ?", "jid-integ-1");
        assertEquals("ACOUSTICS", scoringRow.get("category_name_canonical"));
        assertEquals("SCIE", scoringRow.get("edition_normalized").toString());
        assertEquals("AIS", scoringRow.get("metric_type").toString());
        assertEquals(1.23, (Double) scoringRow.get("value"), 0.001);
        assertEquals("Q1", scoringRow.get("quarter"));
        assertEquals(3, scoringRow.get("rank"));
        assertNotNull(scoringRow.get("build_version"));
    }

    @Test
    void scoringViewValueIsNullWhenNoMatchingMetricFact() {
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("jid-integ-2");
        identity.setTitle("No Metric Journal");
        identityRepository.save(identity);

        // Category for AIS 2023, but no AIS metric fact saved → scoring value should be null
        WosCategoryFact category = new WosCategoryFact();
        category.setId("cf-integ-2");
        category.setJournalId("jid-integ-2");
        category.setYear(2023);
        category.setCategoryNameCanonical("BIOLOGY");
        category.setEditionNormalized(EditionNormalized.SSCI);
        category.setMetricType(MetricType.AIS);
        category.setQuarter("Q2");
        category.setRank(5);
        categoryFactRepository.save(category);

        service.rebuildWosProjections();

        Map<String, Object> scoringRow = jdbcTemplate.queryForMap(
                "SELECT * FROM reporting_read.wos_scoring_view WHERE journal_id = ?", "jid-integ-2");
        assertNull(scoringRow.get("value"));
        assertEquals("BIOLOGY", scoringRow.get("category_name_canonical"));
        assertEquals("Q2", scoringRow.get("quarter"));
        assertEquals(5, scoringRow.get("rank"));
    }
}
