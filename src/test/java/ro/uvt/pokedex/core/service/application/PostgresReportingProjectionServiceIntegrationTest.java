package ro.uvt.pokedex.core.service.application;

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
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService;
import ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService;
import ro.uvt.pokedex.core.service.importing.wos.WosOptimizationProperties;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    private JdbcPostgresMaterializedViewRefreshService materializedViewRefreshService;

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
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        materializedViewRefreshService = new JdbcPostgresMaterializedViewRefreshService(jdbcTemplate);

        MongoRepositoryFactory mongoRepoFactory = new MongoRepositoryFactory(mongoTemplate);
        WosJournalIdentityRepository identityRepository = mongoRepoFactory.getRepository(WosJournalIdentityRepository.class);
        ScholardexForumFactRepository canonicalForumFactRepository = mongoRepoFactory.getRepository(ScholardexForumFactRepository.class);
        ScholardexAuthorFactRepository authorFactRepository = mongoRepoFactory.getRepository(ScholardexAuthorFactRepository.class);
        ScholardexAffiliationFactRepository affiliationFactRepository = mongoRepoFactory.getRepository(ScholardexAffiliationFactRepository.class);
        ScholardexPublicationFactRepository publicationFactRepository = mongoRepoFactory.getRepository(ScholardexPublicationFactRepository.class);
        ScholardexCitationFactRepository citationFactRepository = mongoRepoFactory.getRepository(ScholardexCitationFactRepository.class);
        ScholardexAuthorshipFactRepository authorshipFactRepository = mongoRepoFactory.getRepository(ScholardexAuthorshipFactRepository.class);
        ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository = mongoRepoFactory.getRepository(ScholardexAuthorAffiliationFactRepository.class);
        ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository wosMetricFactRepository = mongoRepoFactory.getRepository(ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository.class);
        ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository wosCategoryFactRepository = mongoRepoFactory.getRepository(ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository.class);
        ro.uvt.pokedex.core.repository.reporting.WosCoverageFactRepository wosCoverageFactRepository = mongoRepoFactory.getRepository(ro.uvt.pokedex.core.repository.reporting.WosCoverageFactRepository.class);

        WosOptimizationProperties optimizationProperties = new WosOptimizationProperties();
        optimizationProperties.setPreflightIndexesEnabled(false);
        optimizationProperties.setProjectionWriteChunkSize(50);

        WosProjectionBuilderService wosBuilder = new WosProjectionBuilderService(
                identityRepository,
                mongoTemplate,
                Mockito.mock(WosIndexMaintenanceService.class),
                optimizationProperties,
                jdbcTemplate,
                txManager,
                org.mockito.Mockito.mock(ro.uvt.pokedex.core.service.application.ReportingDataEpochService.class)
        );

        ScholardexProjectionBuilderService scopusBuilder = new ScholardexProjectionBuilderService(
                canonicalForumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                publicationFactRepository,
                citationFactRepository,
                authorshipFactRepository,
                authorAffiliationFactRepository,
                wosMetricFactRepository,
                wosCategoryFactRepository,
                wosCoverageFactRepository,
                mongoTemplate,
                jdbcTemplate,
                txManager,
                org.mockito.Mockito.mock(ro.uvt.pokedex.core.service.application.ReportingDataEpochService.class)
        );

        PostgresReportingProjectionProperties properties = new PostgresReportingProjectionProperties();
        properties.setEnabled(true);
        properties.setChunkSize(50);
        properties.setStatementTimeoutMs(120_000);

        projectionService = new JdbcPostgresReportingProjectionService(
                mongoTemplate,
                jdbcTemplate,
                txManager,
                properties,
                materializedViewRefreshService,
                wosBuilder,
                scopusBuilder
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
        assertEquals(1L, tableCount("reporting_read.mv_wos_top_rankings_q1_ais"));
        assertEquals(1L, tableCount("reporting_read.mv_scholardex_citation_context"));

        PostgresReportingProjectionService.ProjectionRunSummary incrementalNoChange = projectionService.runIncrementalSync();
        assertEquals("SUCCESS", incrementalNoChange.status());
        long skippedSlices = incrementalNoChange.slices().stream()
                .filter(slice -> "SKIPPED".equals(slice.status()))
                .count();
        assertEquals(2L, skippedSlices);

        // Modify a publication fact to change the Scopus fingerprint
        ScholardexPublicationFact pubFact = mongoTemplate.findById("pub-fact-1", ScholardexPublicationFact.class);
        assert pubFact != null;
        pubFact.setUpdatedAt(Instant.now().plusSeconds(60));
        mongoTemplate.save(pubFact);

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
        assertEquals(1L, tableCount("reporting_read.mv_scholardex_citation_context"));
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

        jdbcTemplate.execute("DROP INDEX reporting_read.uq_mv_scholardex_citation_context_edge");

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

        // WoS journal identity — used by WosProjectionBuilderService and WoS fingerprint
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("j1");
        identity.setTitle("Journal One");
        identity.setPrimaryIssn("1234-5678");
        identity.setEIssn("8765-4321");
        identity.setActive(true);
        identity.setUpdatedAt(now);
        mongoTemplate.save(identity);

        // WoS metric facts
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

        // WoS category facts
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

        // Canonical Scholardex forum fact — H55.3: scholardex_forum_view is projected from canonical
        // forums (scholardex.forum_facts), keyed by the sforum_ id the publication now carries.
        ScholardexForumFact forumFact = new ScholardexForumFact();
        forumFact.setId("forum-1");
        forumFact.setName("Forum One");
        forumFact.setIssn("1234-5678");
        forumFact.setCreatedAt(now);
        forumFact.setUpdatedAt(now);
        mongoTemplate.save(forumFact);

        // Scholardex author fact — used by ScholardexProjectionBuilderService (scholardex.author_facts)
        ScholardexAuthorFact authorFact = new ScholardexAuthorFact();
        authorFact.setId("author-1");
        authorFact.setDisplayName("Author One");
        authorFact.setCreatedAt(now);
        authorFact.setUpdatedAt(now);
        mongoTemplate.save(authorFact);

        // Scholardex affiliation fact — used by ScholardexProjectionBuilderService (scholardex.affiliation_facts)
        ScholardexAffiliationFact affiliationFact = new ScholardexAffiliationFact();
        affiliationFact.setId("aff-1");
        affiliationFact.setName("Affiliation One");
        affiliationFact.setCountry("RO");
        affiliationFact.setCreatedAt(now);
        affiliationFact.setUpdatedAt(now);
        mongoTemplate.save(affiliationFact);

        // Scholardex publication fact — used by ScholardexProjectionBuilderService and Scopus fingerprint
        ScholardexPublicationFact pubFact = new ScholardexPublicationFact();
        pubFact.setId("pub-fact-1");
        pubFact.setTitle("Projection Test Publication");
        pubFact.setEid("EID-1");
        pubFact.setForumId("forum-1");
        pubFact.setOpenAccess(true);
        pubFact.setApproved(true);
        pubFact.setCreatedAt(now);
        pubFact.setUpdatedAt(now);
        mongoTemplate.save(pubFact);

        // Scholardex citation fact — used by ScholardexProjectionBuilderService (scholardex.citation_facts)
        ScholardexCitationFact citation = new ScholardexCitationFact();
        citation.setId("cit-1");
        citation.setCitedPublicationId("pub-fact-1");
        citation.setCitingPublicationId("pub-fact-1");
        citation.setSource("SCOPUS");
        citation.setCreatedAt(now);
        citation.setUpdatedAt(now);
        mongoTemplate.save(citation);

        // Scholardex authorship fact (scholardex.authorship_facts)
        ScholardexAuthorshipFact authorship = new ScholardexAuthorshipFact();
        authorship.setId("authorship-1");
        authorship.setPublicationId("pub-fact-1");
        authorship.setAuthorId("author-1");
        authorship.setSource("SCOPUS");
        authorship.setCreatedAt(now);
        authorship.setUpdatedAt(now);
        mongoTemplate.save(authorship);

        // Scholardex author-affiliation fact (scholardex.author_affiliation_facts)
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
