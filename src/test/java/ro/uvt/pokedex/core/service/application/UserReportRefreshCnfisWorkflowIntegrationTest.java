package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.UserIndicatorResult;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndicatorResultRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportResult;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportStatus;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class UserReportRefreshCnfisWorkflowIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;

    private IndicatorRepository indicatorRepository;
    private IndividualReportRepository individualReportRepository;
    private UserIndicatorResultRepository userIndicatorResultRepository;
    private UserIndividualReportRunRepository userIndividualReportRunRepository;

    private UserReportFacade userReportFacade;
    private UserIndicatorResultService userIndicatorResultService;
    private UserIndividualReportRunService userIndividualReportRunService;

    private UserService userService;
    private ResearcherService researcherService;
    private ScholardexProjectionReadService scholardexProjectionReadService;
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    private ScientificProductionService scientificProductionService;
    private DomainRepository domainRepository;
    private CNFISScoringService2025 cnfisScoringService2025;
    private WoSExtractor woSExtractor;
    private CNFISReportExportService exportService;
    private PublicationEnrichmentLinkerService publicationEnrichmentLinkerService;

    @BeforeEach
    void setup() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "h13_2_user_workflow_test");
        mongoTemplate.getDb().drop();

        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        indicatorRepository = repositoryFactory.getRepository(IndicatorRepository.class);
        individualReportRepository = repositoryFactory.getRepository(IndividualReportRepository.class);
        userIndicatorResultRepository = repositoryFactory.getRepository(UserIndicatorResultRepository.class);
        userIndividualReportRunRepository = repositoryFactory.getRepository(UserIndividualReportRunRepository.class);

        userService = mock(UserService.class);
        researcherService = mock(ResearcherService.class);
        ActivityInstanceRepository activityInstanceRepository = mock(ActivityInstanceRepository.class);
        scholardexProjectionReadService = mock(ScholardexProjectionReadService.class);
        domainRepository = mock(DomainRepository.class);
        ActivityReportingService activityReportingService = mock(ActivityReportingService.class);
        scientificProductionService = mock(ScientificProductionService.class);
        researcherAuthorLookupService = mock(ResearcherAuthorLookupService.class);
        cnfisScoringService2025 = mock(CNFISScoringService2025.class);
        woSExtractor = mock(WoSExtractor.class);
        exportService = mock(CNFISReportExportService.class);
        CacheService cacheService = mock(CacheService.class);
        publicationEnrichmentLinkerService = mock(PublicationEnrichmentLinkerService.class);
        ReportingLookupPort reportingLookupPort = mock(ReportingLookupPort.class);

        userReportFacade = new UserReportFacade(
                userService,
                researcherService,
                indicatorRepository,
                individualReportRepository,
                activityInstanceRepository,
                scholardexProjectionReadService,
                domainRepository,
                activityReportingService,
                scientificProductionService,
                researcherAuthorLookupService,
                cnfisScoringService2025,
                woSExtractor,
                exportService,
                cacheService,
                publicationEnrichmentLinkerService,
                reportingLookupPort
        );

        IndicatorPayloadSerializer payloadSerializer = new IndicatorPayloadSerializer(new ObjectMapper());
        userIndicatorResultService = new UserIndicatorResultService(
                userIndicatorResultRepository,
                indicatorRepository,
                userService,
                userReportFacade,
                payloadSerializer
        );
        userIndividualReportRunService = new UserIndividualReportRunService(
                userIndividualReportRunRepository,
                individualReportRepository,
                userService,
                userIndicatorResultService,
                userReportFacade
        );
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void refreshAllIndicatorsPersistsNewRunAndCnfisExportUsesCurrentUserContext() throws Exception {
        seedWorkflowDefinitions();
        seedPersistedLatestIndicatorResults();
        stubLookupAndScoringSeams();

        assertTrue(userReportFacade.findIndividualReportById("rep-1").isPresent());

        Optional<IndividualReportRunDto> initialRunOpt =
                userIndividualReportRunService.getOrCreateLatestRun("user@uvt.ro", "rep-1");

        assertTrue(initialRunOpt.isPresent());
        IndividualReportRunDto initialRun = initialRunOpt.orElseThrow();
        assertEquals(IndividualReportRunDto.Source.BUILT, initialRun.source());
        assertEquals(Map.of("ind-1", 4.0, "ind-2", 6.0), initialRun.indicatorScoresByIndicatorId());
        assertEquals(Map.of(0, 10.0), initialRun.criteriaScores());

        List<UserIndividualReportRun> runsAfterInitialView = runsNewestFirst();
        assertEquals(1, runsAfterInitialView.size());
        UserIndividualReportRun persistedInitialRun = runsAfterInitialView.getFirst();
        assertEquals(UserIndividualReportRun.Status.READY, persistedInitialRun.getStatus());
        assertEquals(2, snapshotResults().size());
        assertEquals(List.of(4.0, 6.0), snapshotResults().stream().map(UserIndicatorResult::getTotalScore).sorted().toList());
        assertEquals(List.of(0, 0), latestRefreshVersionsSorted());

        Optional<IndividualReportRunDto> refreshedRunOpt =
                userIndividualReportRunService.refreshRunWithAllIndicators("user@uvt.ro", "rep-1");

        assertTrue(refreshedRunOpt.isPresent());
        IndividualReportRunDto refreshedRun = refreshedRunOpt.orElseThrow();
        assertEquals(IndividualReportRunDto.Source.BUILT, refreshedRun.source());
        assertEquals(Map.of("ind-1", 4.0, "ind-2", 6.0), refreshedRun.indicatorScoresByIndicatorId());
        assertEquals(Map.of(0, 10.0), refreshedRun.criteriaScores());

        List<UserIndividualReportRun> runsAfterRefresh = runsNewestFirst();
        assertEquals(2, runsAfterRefresh.size());
        UserIndividualReportRun persistedRefreshedRun = runsAfterRefresh.getFirst();
        assertNotEquals(persistedInitialRun.getId(), persistedRefreshedRun.getId());
        assertTrue(persistedRefreshedRun.getCreatedAt().isAfter(persistedInitialRun.getCreatedAt()));
        assertEquals(UserIndividualReportRun.Status.READY, persistedRefreshedRun.getStatus());

        List<UserIndicatorResult> snapshots = snapshotResults();
        assertEquals(4, snapshots.size());
        List<UserIndicatorResult> refreshedSnapshots = snapshots.stream()
                .filter(snapshot -> persistedRefreshedRun.getIndicatorResultIds().contains(snapshot.getId()))
                .toList();
        assertEquals(2, refreshedSnapshots.size());
        assertTrue(refreshedSnapshots.stream().allMatch(snapshot -> snapshot.getMode() == UserIndicatorResult.Mode.SNAPSHOT));
        assertTrue(refreshedSnapshots.stream().allMatch(snapshot -> snapshot.getSourceReportId().equals("rep-1")));
        assertTrue(refreshedSnapshots.stream().allMatch(snapshot -> snapshot.getRefreshVersion() == 1));

        assertEquals(List.of(1, 1), latestRefreshVersionsSorted());

        UserWorkbookExportResult exportResult =
                userReportFacade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, exportResult.status());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", exportResult.contentType());
        assertEquals("data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx", exportResult.fileName());

        ArgumentCaptor<List<Publication>> publicationCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<CNFISReport2025>> reportCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> authorIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(exportService).generateCNFISReportWorkbook(
                publicationCaptor.capture(),
                reportCaptor.capture(),
                any(),
                authorIdsCaptor.capture(),
                eq(false)
        );
        assertEquals(List.of("p1"), publicationCaptor.getValue().stream().map(Publication::getId).toList());
        assertEquals(1, reportCaptor.getValue().size());
        assertEquals(List.of("a1"), authorIdsCaptor.getValue());
        verify(publicationEnrichmentLinkerService).linkWosEnrichment(eq(publicationCaptor.getValue().getFirst()), any(), any(), any());
    }

    private void seedWorkflowDefinitions() {
        Indicator indicatorOne = new Indicator();
        indicatorOne.setId("ind-1");
        indicatorOne.setName("Publication Score A");
        indicatorOne.setOutputType(Indicator.Type.PUBLICATIONS);
        indicatorOne.setSelector(Indicator.Selector.ALL);

        Indicator indicatorTwo = new Indicator();
        indicatorTwo.setId("ind-2");
        indicatorTwo.setName("Publication Score B");
        indicatorTwo.setOutputType(Indicator.Type.PUBLICATIONS);
        indicatorTwo.setSelector(Indicator.Selector.ALL);

        indicatorRepository.saveAll(List.of(indicatorOne, indicatorTwo));

        AbstractReport.Criterion criterion = new AbstractReport.Criterion();
        criterion.setName("Combined score");
        criterion.setIndicatorIndices(List.of(0, 1));
        criterion.setThresholds(new ArrayList<>());

        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setTitle("User workflow report");
        report.setDescription("Workflow fixture");
        report.setIndicators(List.of(indicatorOne, indicatorTwo));
        report.setCriteria(List.of(criterion));
        individualReportRepository.save(report);
    }

    private void seedPersistedLatestIndicatorResults() {
        userIndicatorResultRepository.save(existingLatest("latest-ind-1", "ind-1", 1.0, 0));
        userIndicatorResultRepository.save(existingLatest("latest-ind-2", "ind-2", 2.0, 0));
    }

    private UserIndicatorResult existingLatest(String id, String indicatorId, double totalScore, int refreshVersion) {
        UserIndicatorResult result = new UserIndicatorResult();
        result.setId(id);
        result.setUserEmail("user@uvt.ro");
        result.setResearcherId("r1");
        result.setIndicatorId(indicatorId);
        result.setMode(UserIndicatorResult.Mode.LATEST);
        result.setSourceType(UserIndicatorResult.SourceType.APPLY_PAGE);
        result.setFingerprint("stale-" + indicatorId);
        result.setViewName("user/indicators-apply-publications");
        result.setRawGraph("{\"total\":\"" + totalScore + "\"}");
        result.setTotalScore(totalScore);
        result.setTotalCount(1);
        result.setQuarterLabels(List.of("Q4"));
        result.setQuarterValues(List.of(1));
        result.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        result.setUpdatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        result.setRefreshVersion(refreshVersion);
        return result;
    }

    private void stubLookupAndScoringSeams() throws Exception {
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r1");

        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setScopusId(List.of("a1"));

        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        Publication publication = new Publication();
        publication.setId("p1");
        publication.setTitle("Workflow Publication");
        publication.setAuthors(List.of("a1"));
        publication.setForum("f1");
        publication.setCoverDate("2022-05-10");

        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(researcher)).thenReturn(List.of("a1"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(forum));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(woSExtractor.findPublicationWosId(publication)).thenReturn(publication);
        when(publicationEnrichmentLinkerService.linkWosEnrichment(any(), any(), any(), any()))
                .thenReturn(new PublicationEnrichmentLinkerService.LinkResult(
                        PublicationEnrichmentLinkerService.LinkState.LINKED,
                        "linked",
                        "pub-fact-1",
                        null
                ));
        when(cnfisScoringService2025.getReport(eq(publication), eq(allDomain))).thenReturn(new CNFISReport2025());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{1, 2, 3});

        when(scientificProductionService.calculateScientificProductionScore(anyList(), any(Indicator.class)))
                .thenAnswer(invocation -> {
                    Indicator indicator = invocation.getArgument(1, Indicator.class);
                    double authorScore = "ind-1".equals(indicator.getId()) ? 4.0 : 6.0;
                    Score total = score(authorScore, "TOTAL");
                    Score publicationScore = score(authorScore, "Q1");
                    return new java.util.LinkedHashMap<>(Map.of(
                            "total", total,
                            "Workflow Publication", publicationScore
                    ));
                });
    }

    private Score score(double authorScore, String quarter) {
        Score score = new Score();
        score.setAuthorScore(authorScore);
        score.setQuarter(quarter);
        score.setCategory("CATEGORY");
        return score;
    }

    private List<UserIndividualReportRun> runsNewestFirst() {
        return userIndividualReportRunRepository.findAll().stream()
                .sorted(Comparator.comparing(UserIndividualReportRun::getCreatedAt).reversed())
                .toList();
    }

    private List<UserIndicatorResult> snapshotResults() {
        return userIndicatorResultRepository.findAll().stream()
                .filter(result -> result.getMode() == UserIndicatorResult.Mode.SNAPSHOT)
                .toList();
    }

    private List<Integer> latestRefreshVersionsSorted() {
        return userIndicatorResultRepository.findAll().stream()
                .filter(result -> result.getMode() == UserIndicatorResult.Mode.LATEST)
                .map(UserIndicatorResult::getRefreshVersion)
                .sorted()
                .toList();
    }
}
