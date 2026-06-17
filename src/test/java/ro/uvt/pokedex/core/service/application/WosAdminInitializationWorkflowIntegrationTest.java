package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactBuildCheckpoint;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosFactBuildCheckpointRepository;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.application.model.WosEnrichmentRunSummaryDto;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;
import ro.uvt.pokedex.core.service.importing.wos.GovAisRisImportEventParser;
import ro.uvt.pokedex.core.service.importing.wos.OfficialWosJsonImportEventParser;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuildCheckpointService;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosIdentityResolutionService;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventIngestionService;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventParser;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventParserOrchestrator;
import ro.uvt.pokedex.core.service.importing.wos.WosOptimizationProperties;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class WosAdminInitializationWorkflowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("core_h13_1")
            .withUsername("core")
            .withPassword("core");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private JdbcTemplate jdbcTemplate;

    private RankingMaintenanceFacade rankingMaintenanceFacade;
    private PostgresWosRankingReadPort rankingReadPort;

    private WosImportEventRepository importEventRepository;
    private WosJournalIdentityRepository journalIdentityRepository;
    private WosMetricFactRepository metricFactRepository;
    private WosCategoryFactRepository categoryFactRepository;
    private ro.uvt.pokedex.core.repository.reporting.WosCoverageFactRepository coverageFactRepository;
    private WosFactBuildCheckpointRepository checkpointRepository;

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
        mongoTemplate = new MongoTemplate(mongoClient, "h13_1_wos_workflow_test");
        mongoTemplate.getDb().drop();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbcTemplate = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);

        MongoRepositoryFactory mongoRepoFactory = new MongoRepositoryFactory(mongoTemplate);
        importEventRepository = mongoRepoFactory.getRepository(WosImportEventRepository.class);
        journalIdentityRepository = mongoRepoFactory.getRepository(WosJournalIdentityRepository.class);
        metricFactRepository = mongoRepoFactory.getRepository(WosMetricFactRepository.class);
        categoryFactRepository = mongoRepoFactory.getRepository(WosCategoryFactRepository.class);
        coverageFactRepository = mongoRepoFactory.getRepository(ro.uvt.pokedex.core.repository.reporting.WosCoverageFactRepository.class);
        WosIdentityConflictRepository identityConflictRepository =
                mongoRepoFactory.getRepository(WosIdentityConflictRepository.class);
        WosFactConflictRepository factConflictRepository =
                mongoRepoFactory.getRepository(WosFactConflictRepository.class);
        checkpointRepository = mongoRepoFactory.getRepository(WosFactBuildCheckpointRepository.class);
        ScopusForumFactRepository scopusForumFactRepository =
                mongoRepoFactory.getRepository(ScopusForumFactRepository.class);
        ScholardexForumFactRepository scholardexForumFactRepository =
                mongoRepoFactory.getRepository(ScholardexForumFactRepository.class);
        ScholardexSourceLinkRepository sourceLinkRepository =
                mongoRepoFactory.getRepository(ScholardexSourceLinkRepository.class);
        ScholardexIdentityConflictRepository scholardexIdentityConflictRepository =
                mongoRepoFactory.getRepository(ScholardexIdentityConflictRepository.class);
        ScholardexPublicationFactRepository scholardexPublicationFactRepository =
                mongoRepoFactory.getRepository(ScholardexPublicationFactRepository.class);

        ObjectMapper objectMapper = new ObjectMapper();
        WosOptimizationProperties optimizationProperties = new WosOptimizationProperties();
        optimizationProperties.setPreflightIndexesEnabled(false);
        optimizationProperties.setFactChunkSize(10);
        optimizationProperties.setIngestPersistBatchSize(10);
        optimizationProperties.setProjectionWriteChunkSize(10);
        optimizationProperties.setIdentityLruMaxSize(128);

        WosIndexMaintenanceService indexMaintenanceService = new WosIndexMaintenanceService(mongoTemplate);
        WosImportEventIngestionService ingestionService = new WosImportEventIngestionService(
                importEventRepository,
                objectMapper,
                optimizationProperties,
                indexMaintenanceService
        );

        List<WosImportEventParser> parsers = List.of(
                new GovAisRisImportEventParser(objectMapper),
                new OfficialWosJsonImportEventParser(objectMapper)
        );
        WosImportEventParserOrchestrator parserOrchestrator =
                new WosImportEventParserOrchestrator(importEventRepository, parsers);
        WosIdentityResolutionService identityResolutionService =
                new WosIdentityResolutionService(journalIdentityRepository, identityConflictRepository);
        WosFactBuildCheckpointService checkpointService =
                new WosFactBuildCheckpointService(checkpointRepository);
        WosFactBuilderService factBuilderService = new WosFactBuilderService(
                parserOrchestrator,
                identityResolutionService,
                metricFactRepository,
                categoryFactRepository,
                coverageFactRepository,
                factConflictRepository,
                mongoTemplate,
                checkpointService,
                indexMaintenanceService,
                optimizationProperties,
                new SimpleMeterRegistry()
        );
        ScholardexSourceLinkService sourceLinkService =
                new ScholardexSourceLinkService(sourceLinkRepository, scholardexIdentityConflictRepository);
        ConflictRecorder conflictRecorder = new ConflictRecorder(scholardexIdentityConflictRepository, sourceLinkService);
        ForumMergeEngine forumMergeEngine = new ForumMergeEngine(
                journalIdentityRepository,
                scopusForumFactRepository,
                scholardexForumFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                new ForumMergeSafetyRule(),
                conflictRecorder
        );
        WosScholardexOnboardingService onboardingService = new WosScholardexOnboardingService(
                journalIdentityRepository,
                sourceLinkService,
                scholardexPublicationFactRepository,
                conflictRecorder,
                forumMergeEngine
        );
        WosProjectionBuilderService projectionBuilderService = new WosProjectionBuilderService(
                journalIdentityRepository,
                mongoTemplate,
                indexMaintenanceService,
                optimizationProperties,
                jdbcTemplate,
                txManager
        );
        WosParityReconciliationService parityReconciliationService = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                objectMapper,
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );
        WosBigBangMigrationService migrationService = new WosBigBangMigrationService(
                ingestionService,
                factBuilderService,
                onboardingService,
                projectionBuilderService,
                parityReconciliationService,
                parserOrchestrator,
                importEventRepository,
                journalIdentityRepository,
                metricFactRepository,
                categoryFactRepository,
                identityConflictRepository,
                factConflictRepository,
                jdbcTemplate,
                mongoTemplate
        );

        rankingMaintenanceFacade = new RankingMaintenanceFacade(
                projectionBuilderService,
                indexMaintenanceService,
                migrationService
        );
        rankingReadPort = new PostgresWosRankingReadPort(namedParameterJdbcTemplate);
        ReflectionTestUtils.setField(ingestionService, "officialWosJsonDirectory", "wos-json-1997-2019");
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void adminWorkflowBuildsWosFactsEnrichesThemAndProjectsReadableState() throws Exception {
        Path loadedDir = Files.createTempDirectory("h13-1-wos-loaded");
        createAis2023Workbook(loadedDir.resolve("AIS_2023.xlsx"));
        ReflectionTestUtils.setField(
                extractMigrationService(),
                "migrationDataDirectory",
                loadedDir.toString()
        );

        MigrationStepResult ingestStep = rankingMaintenanceFacade.ingestWosEvents("workflow-2026");
        assertTrue(ingestStep.executed());
        assertEquals(1L, importEventRepository.count());

        MigrationStepResult buildFactsStep =
                rankingMaintenanceFacade.buildWosFactsFromEvents(null, "workflow-2026", true);
        assertTrue(buildFactsStep.executed());
        assertEquals(1L, journalIdentityRepository.count());
        assertEquals(1L, metricFactRepository.count());
        assertEquals(1L, categoryFactRepository.count());

        WosFactBuildCheckpoint checkpoint = checkpointRepository
                .findById(WosFactBuildCheckpointService.WOS_FACT_BUILD_PIPELINE_KEY)
                .orElseThrow();
        assertEquals(0, checkpoint.getLastCompletedBatch());
        assertEquals("workflow-2026", checkpoint.getSourceVersion());

        WosCategoryFact categoryFactBeforeEnrichment = categoryFactRepository.findAll().getFirst();
        assertNull(categoryFactBeforeEnrichment.getRank());
        assertNull(categoryFactBeforeEnrichment.getQuarter());
        assertNull(categoryFactBeforeEnrichment.getQuartileRank());

        WosEnrichmentRunSummaryDto enrichmentSummary =
                rankingMaintenanceFacade.runWosCategoryRankingEnrichmentWithSummary();
        assertTrue(enrichmentSummary.executed());
        assertEquals(1, enrichmentSummary.processed());
        assertEquals(1, enrichmentSummary.computed());

        WosCategoryFact categoryFactAfterEnrichment = categoryFactRepository.findAll().getFirst();
        assertEquals(1, categoryFactAfterEnrichment.getRank());
        assertEquals("Q1", categoryFactAfterEnrichment.getQuarter());
        assertEquals(1, categoryFactAfterEnrichment.getQuartileRank());

        ImportProcessingResult projectionResult = rankingMaintenanceFacade.rebuildWosProjections();
        assertEquals(2L, projectionResult.getImportedCount());
        assertEquals(1L, tableCount("reporting_read.wos_ranking_view"));
        assertEquals(1L, tableCount("reporting_read.wos_metric_fact"));
        assertEquals(1L, tableCount("reporting_read.wos_category_fact"));
        assertEquals(1L, tableCount("reporting_read.wos_scoring_view"));

        String journalId = journalIdentityRepository.findAll().getFirst().getId();
        String primaryIssn = journalIdentityRepository.findAll().getFirst().getPrimaryIssn();

        Integer projectedRank = jdbcTemplate.queryForObject(
                "SELECT rank FROM reporting_read.wos_scoring_view WHERE journal_id = ?",
                Integer.class,
                journalId
        );
        String projectedQuarter = jdbcTemplate.queryForObject(
                "SELECT quarter FROM reporting_read.wos_scoring_view WHERE journal_id = ?",
                String.class,
                journalId
        );
        Integer projectedQuartileRank = jdbcTemplate.queryForObject(
                "SELECT quartile_rank FROM reporting_read.wos_scoring_view WHERE journal_id = ?",
                Integer.class,
                journalId
        );
        assertEquals(1, projectedRank);
        assertEquals("Q1", projectedQuarter);
        assertEquals(1, projectedQuartileRank);

        // Unauthorized/admin-only behavior is already covered by AdminInitializationSecurityContractTest.
        WosRankingPageResponse rankingPage = rankingReadPort.search(0, 10, "name", "asc", "Workflow Journal");
        assertEquals(1L, rankingPage.totalItems());
        assertEquals("Workflow Journal", rankingPage.items().getFirst().name());
        assertEquals(primaryIssn, rankingPage.items().getFirst().issn());
    }

    private WosBigBangMigrationService extractMigrationService() {
        return (WosBigBangMigrationService) ReflectionTestUtils.getField(
                rankingMaintenanceFacade,
                "wosBigBangMigrationService"
        );
    }

    private long tableCount(String tableName) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count == null ? 0L : count;
    }

    private void createAis2023Workbook(Path file) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Title");
            header.createCell(1).setCellValue("ISSN");
            header.createCell(2).setCellValue("EISSN");
            header.createCell(3).setCellValue("Category");
            header.createCell(4).setCellValue("Edition");
            header.createCell(5).setCellValue("AIS");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Workflow Journal");
            row.createCell(1).setCellValue("1234-5678");
            row.createCell(2).setCellValue("8765-4321");
            row.createCell(3).setCellValue("COMPUTER SCIENCE");
            row.createCell(4).setCellValue("SCIE");
            row.createCell(5).setCellValue(2.75d);

            try (FileOutputStream outputStream = new FileOutputStream(file.toFile())) {
                workbook.write(outputStream);
            }
        }
        assertTrue(Files.exists(file));
        assertNotNull(file);
    }
}
