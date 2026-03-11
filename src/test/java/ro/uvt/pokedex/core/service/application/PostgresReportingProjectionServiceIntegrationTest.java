package ro.uvt.pokedex.core.service.application;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
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
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
class PostgresReportingProjectionServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("core_h22_3")
            .withUsername("core")
            .withPassword("core");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private JdbcTemplate jdbcTemplate;
    private JdbcPostgresReportingProjectionService projectionService;

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
        mongoTemplate = new MongoTemplate(mongoClient, "h22_projection_test");
        mongoTemplate.getDb().drop();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbcTemplate = new JdbcTemplate(dataSource);

        PostgresReportingProjectionProperties properties = new PostgresReportingProjectionProperties();
        properties.setEnabled(true);
        properties.setChunkSize(50);
        properties.setStatementTimeoutMs(120_000);
        properties.setDryRun(false);

        projectionService = new JdbcPostgresReportingProjectionService(
                mongoTemplate,
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                properties
        );
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void fullRebuildAndIncrementalSyncMaintainDeterministicState() {
        seedMongoProjectionSources();

        PostgresReportingProjectionService.ProjectionRunSummary full = projectionService.runFullRebuild();
        assertEquals("SUCCESS", full.status());
        assertEquals(1L, tableCount("reporting_read.wos_ranking_view"));
        assertEquals(1L, tableCount("reporting_read.wos_metric_fact"));
        assertEquals(1L, tableCount("reporting_read.wos_category_fact"));
        assertEquals(1L, tableCount("reporting_read.wos_scoring_view"));
        assertEquals(1L, tableCount("reporting_read.scholardex_publication_view"));
        assertEquals(1L, tableCount("reporting_read.scholardex_author_view"));
        assertEquals(1L, tableCount("reporting_read.scholardex_affiliation_view"));
        assertEquals(1L, tableCount("reporting_read.scholardex_forum_view"));
        assertEquals(1L, tableCount("reporting_read.scholardex_citation_fact"));
        assertEquals(1L, tableCount("reporting_read.scholardex_authorship_fact"));
        assertEquals(1L, tableCount("reporting_read.scholardex_author_affiliation_fact"));

        PostgresReportingProjectionService.ProjectionRunSummary incrementalNoChange = projectionService.runIncrementalSync();
        assertEquals("SUCCESS", incrementalNoChange.status());
        long skippedSlices = incrementalNoChange.slices().stream()
                .filter(slice -> "SKIPPED".equals(slice.status()))
                .count();
        assertEquals(2L, skippedSlices);

        ScholardexPublicationView publication = mongoTemplate.findById("pub-1", ScholardexPublicationView.class);
        assertNotNull(publication);
        publication.setUpdatedAt(Instant.now().plusSeconds(60));
        mongoTemplate.save(publication);

        PostgresReportingProjectionService.ProjectionRunSummary incrementalChanged = projectionService.runIncrementalSync();
        assertEquals("SUCCESS", incrementalChanged.status());
        long successfulSlices = incrementalChanged.slices().stream()
                .filter(slice -> "SUCCESS".equals(slice.status()))
                .count();
        assertEquals(1L, successfulSlices);
        assertEquals("scopus", incrementalChanged.slices().stream()
                .filter(slice -> "SUCCESS".equals(slice.status()))
                .findFirst()
                .orElseThrow()
                .sliceName());

        long checkpoints = tableCount("reporting_read.projection_checkpoint");
        assertEquals(2L, checkpoints);
    }

    @Test
    void failedSliceDoesNotAdvanceCheckpoint() {
        seedMongoProjectionSources();
        PostgresReportingProjectionService.ProjectionRunSummary initial = projectionService.runFullRebuild();
        assertEquals("SUCCESS", initial.status());

        String checkpointBefore = jdbcTemplate.queryForObject(
                "SELECT source_fingerprint FROM reporting_read.projection_checkpoint WHERE slice_name = 'scopus'",
                String.class
        );

        ScholardexCitationFact invalidCitation = new ScholardexCitationFact();
        invalidCitation.setId("cit-invalid");
        invalidCitation.setCitedPublicationId("missing-publication");
        invalidCitation.setCitingPublicationId("missing-publication-2");
        invalidCitation.setSource("SCOPUS");
        invalidCitation.setUpdatedAt(Instant.now().plusSeconds(120));
        mongoTemplate.save(invalidCitation);

        PostgresReportingProjectionService.ProjectionRunSummary failed = projectionService.runIncrementalSync();
        assertEquals("FAILED", failed.status());

        String checkpointAfter = jdbcTemplate.queryForObject(
                "SELECT source_fingerprint FROM reporting_read.projection_checkpoint WHERE slice_name = 'scopus'",
                String.class
        );
        assertEquals(checkpointBefore, checkpointAfter);
    }

    private void seedMongoProjectionSources() {
        Instant now = Instant.parse("2026-03-11T10:00:00Z");

        WosRankingView ranking = new WosRankingView();
        ranking.setId("j1");
        ranking.setName("Journal One");
        ranking.setIssn("1234-5678");
        ranking.setIssnNorm("12345678");
        ranking.setEIssn("8765-4321");
        ranking.setEIssnNorm("87654321");
        ranking.setAlternativeIssns(List.of("1111-2222"));
        ranking.setAlternativeIssnsNorm(List.of("11112222"));
        ranking.setAlternativeNames(List.of("J One"));
        ranking.setLatestAisYear(2025);
        ranking.setLatestRisYear(2025);
        ranking.setLatestEditionNormalized(EditionNormalized.SCIE);
        ranking.setUpdatedAt(now);
        mongoTemplate.save(ranking);

        WosMetricFact metricFact = new WosMetricFact();
        metricFact.setId("metric-1");
        metricFact.setJournalId("j1");
        metricFact.setYear(2025);
        metricFact.setMetricType(MetricType.AIS);
        metricFact.setValue(2.5);
        metricFact.setSourceType(WosSourceType.GOV_AIS_RIS);
        metricFact.setSourceVersion("v2026");
        metricFact.setCreatedAt(now);
        mongoTemplate.save(metricFact);

        WosCategoryFact categoryFact = new WosCategoryFact();
        categoryFact.setId("category-1");
        categoryFact.setJournalId("j1");
        categoryFact.setYear(2025);
        categoryFact.setCategoryNameCanonical("COMPUTER SCIENCE");
        categoryFact.setEditionNormalized(EditionNormalized.SCIE);
        categoryFact.setMetricType(MetricType.AIS);
        categoryFact.setQuarter("Q1");
        categoryFact.setQuartileRank(10);
        categoryFact.setRank(50);
        categoryFact.setSourceType(WosSourceType.GOV_AIS_RIS);
        categoryFact.setSourceVersion("v2026");
        categoryFact.setCreatedAt(now);
        mongoTemplate.save(categoryFact);

        WosScoringView scoringView = new WosScoringView();
        scoringView.setId("score-1");
        scoringView.setJournalId("j1");
        scoringView.setYear(2025);
        scoringView.setCategoryNameCanonical("COMPUTER SCIENCE");
        scoringView.setEditionNormalized(EditionNormalized.SCIE);
        scoringView.setMetricType(MetricType.AIS);
        scoringView.setValue(2.5);
        scoringView.setQuarter("Q1");
        scoringView.setQuartileRank(10);
        scoringView.setRank(50);
        scoringView.setUpdatedAt(now);
        mongoTemplate.save(scoringView);

        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("pub-1");
        publication.setEid("EID-1");
        publication.setTitle("Projection Test Publication");
        publication.setAuthorIds(List.of("author-1"));
        publication.setAffiliationIds(List.of("aff-1"));
        publication.setForumId("forum-1");
        publication.setCitingPublicationIds(List.of("pub-2"));
        publication.setCitedByCount(1);
        publication.setOpenAccess(true);
        publication.setApproved(true);
        publication.setBuildAt(now);
        publication.setUpdatedAt(now);
        mongoTemplate.save(publication);

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("author-1");
        author.setName("Author One");
        author.setAffiliationIds(List.of("aff-1"));
        author.setBuildAt(now);
        author.setUpdatedAt(now);
        mongoTemplate.save(author);

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-1");
        forum.setPublicationName("Forum One");
        forum.setIssn("1234-5678");
        forum.setBuildAt(now);
        forum.setUpdatedAt(now);
        mongoTemplate.save(forum);

        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setId("aff-1");
        affiliation.setName("Affiliation One");
        affiliation.setCountry("RO");
        affiliation.setBuildAt(now);
        affiliation.setUpdatedAt(now);
        mongoTemplate.save(affiliation);

        ScholardexCitationFact citation = new ScholardexCitationFact();
        citation.setId("cit-1");
        citation.setCitedPublicationId("pub-1");
        citation.setCitingPublicationId("pub-1");
        citation.setSource("SCOPUS");
        citation.setCreatedAt(now);
        citation.setUpdatedAt(now);
        mongoTemplate.save(citation);

        ScholardexAuthorshipFact authorship = new ScholardexAuthorshipFact();
        authorship.setId("authorship-1");
        authorship.setPublicationId("pub-1");
        authorship.setAuthorId("author-1");
        authorship.setSource("SCOPUS");
        authorship.setCreatedAt(now);
        authorship.setUpdatedAt(now);
        mongoTemplate.save(authorship);

        ScholardexAuthorAffiliationFact authorAffiliation = new ScholardexAuthorAffiliationFact();
        authorAffiliation.setId("author-aff-1");
        authorAffiliation.setAuthorId("author-1");
        authorAffiliation.setAffiliationId("aff-1");
        authorAffiliation.setSource("SCOPUS");
        authorAffiliation.setCreatedAt(now);
        authorAffiliation.setUpdatedAt(now);
        mongoTemplate.save(authorAffiliation);
    }

    private long tableCount(String tableName) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count == null ? 0L : count;
    }
}
