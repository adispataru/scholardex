package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorApplyViewModel;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportStatus;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class UserReportFacadeTest {

    @Mock
    private UserService userService;
    @Mock
    private ResearcherService researcherService;
    @Mock
    private IndicatorRepository indicatorRepository;
    @Mock
    private IndividualReportRepository individualReportRepository;
    @Mock
    private ActivityInstanceRepository activityInstanceRepository;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private DomainRepository domainRepository;
    @Mock
    private ActivityReportingService activityReportingService;
    @Mock
    private ScientificProductionService scientificProductionService;
    @Mock
    private CNFISScoringService2025 cnfiSScoringService2025;
    @Mock
    private WoSExtractor woSExtractor;
    @Mock
    private CNFISReportExportService exportService;
    @Mock
    private CacheService cacheService;
    @Mock
    private PublicationEnrichmentLinkerService publicationEnrichmentLinkerService;
    @Mock
    private ReportingLookupPort reportingLookupPort;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @Mock
    private EffectiveAuthorshipReadService effectiveAuthorshipReadService;

    @InjectMocks
    private UserReportFacade facade;

    @BeforeEach
    void setUpLookupService() {
        lenient().when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class)))
                .thenAnswer(invocation -> {
                    Researcher researcher = invocation.getArgument(0);
                    return researcher.getScopusId() == null ? List.of() : researcher.getScopusId();
                });
        lenient().when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    var ids = (java.util.Collection<String>) invocation.getArgument(0);
                    return ids.stream().map(id -> {
                        var author = new ScholardexAuthorView();
                        author.setId(id);
                        return author;
                    }).toList();
                });
    }

    @Test
    void buildIndicatorsViewReturnsRepositoryValues() {
        Indicator i = new Indicator();
        i.setName("I1");
        when(indicatorRepository.findAll()).thenReturn(List.of(i));

        var vm = facade.buildIndicatorsView("user@uvt.ro");

        assertEquals(1, vm.indicators().size());
        assertEquals("I1", vm.indicators().getFirst().getName());
    }

    @Test
    void buildIndividualReportsListViewReturnsRepositoryValues() {
        IndividualReport report = new IndividualReport();
        report.setTitle("R1");
        when(individualReportRepository.findAll()).thenReturn(List.of(report));

        var vm = facade.buildIndividualReportsListView("user@uvt.ro");

        assertEquals(1, vm.individualReports().size());
        assertEquals("R1", vm.individualReports().getFirst().getTitle());
    }

    @Test
    void buildIndicatorWorkbookExportReturnsEmptyWhenIndicatorMissing() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("i1")).thenReturn(Optional.empty());

        var result = facade.buildIndicatorWorkbookExport("user@uvt.ro", "i1");

        assertTrue(result.isEmpty());
    }

    @Test
    void buildUserCnfisWorkbookExportReturnsNotFoundWhenResearcherMissing() throws Exception {
        User user = new User();
        user.setEmail("user@uvt.ro");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.NOT_FOUND, result.status());
    }

    @Test
    void buildUserCnfisWorkbookExportReturnsUnauthorizedWhenUserMissing() throws Exception {
        when(userService.getUserByEmail("missing@uvt.ro")).thenReturn(Optional.empty());

        var result = facade.buildUserCnfisWorkbookExport("missing@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.UNAUTHORIZED, result.status());
    }

    @Test
    void buildUserCnfisWorkbookExportReturnsOkWhenDataAvailable() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        publication.setCoverDate("2022-01-01");
        publication.setForum("f1");

        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(publication));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{1, 2});

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        assertEquals("data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx", result.fileName());
    }

    @Test
    void buildUserCnfisWorkbookExportAppliesInclusiveYearBoundaries() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        ScholardexPublicationView pStart = new ScholardexPublicationView();
        pStart.setId("pStart");
        pStart.setCoverDate("2021-01-01");
        pStart.setForum("f1");

        ScholardexPublicationView pIn = new ScholardexPublicationView();
        pIn.setId("pIn");
        pIn.setCoverDate("2022-06-01");
        pIn.setForum("f1");

        ScholardexPublicationView pEnd = new ScholardexPublicationView();
        pEnd.setId("pEnd");
        pEnd.setCoverDate("2024-12-01");
        pEnd.setForum("f1");

        ScholardexPublicationView pOut = new ScholardexPublicationView();
        pOut.setId("pOut");
        pOut.setCoverDate("2025-01-01");
        pOut.setForum("f1");

        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(pStart, pIn, pEnd, pOut));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{1});

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        // only in-range publications should be enriched/saved (2021..2024 inclusive)
        verify(publicationEnrichmentLinkerService).linkWosEnrichment(eq("pStart"), any(), any(), any(), any(), any(), any());
        verify(publicationEnrichmentLinkerService).linkWosEnrichment(eq("pIn"), any(), any(), any(), any(), any(), any());
        verify(publicationEnrichmentLinkerService).linkWosEnrichment(eq("pEnd"), any(), any(), any(), any(), any(), any());
        verify(scholardexProjectionReadService, never()).savePublicationView(any());
    }

    @Test
    void buildLegacyUserCnfisWorkbookExportReturnsNotFoundWhenNoAuthors() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());

        var result = facade.buildLegacyUserCnfisWorkbookExport("user@uvt.ro");

        assertEquals(UserWorkbookExportStatus.NOT_FOUND, result.status());
    }

    @Test
    void buildLegacyUserCnfisWorkbookExportReturnsUnauthorizedWhenUserMissing() throws Exception {
        when(userService.getUserByEmail("missing@uvt.ro")).thenReturn(Optional.empty());

        var result = facade.buildLegacyUserCnfisWorkbookExport("missing@uvt.ro");

        assertEquals(UserWorkbookExportStatus.UNAUTHORIZED, result.status());
    }

    @Test
    void buildIndicatorWorkbookExportPublicationsContainsExpectedHeadersAndRow() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Author One");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        publication.setTitle("Paper One");
        publication.setAuthors(List.of("a1"));
        publication.setForum("f1");
        publication.setVolume("12");
        publication.setCoverDate("2023-01-01");

        Score publicationScore = new Score();
        publicationScore.setCoreRankingEquivalent("Q1");
        publicationScore.setScore(10.0);
        publicationScore.setAuthorScore(5.0);

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("i1")).thenReturn(Optional.of(indicator));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(forum));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(indicator)))
                .thenReturn(Map.of("Paper One", publicationScore));
        when(cacheService.getAuthorCache()).thenReturn(Map.of("a1", author));

        var result = facade.buildIndicatorWorkbookExport("user@uvt.ro", "i1");

        assertTrue(result.isPresent());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.get().workbookBytes()))) {
            var sheet = workbook.getSheet("Publications");
            assertEquals("Title", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Authors", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Paper One", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Author One", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Forum One", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("2023", sheet.getRow(1).getCell(4).getStringCellValue());
        }
    }

    @Test
    void buildIndicatorWorkbookExportPublicationsUsesBlankYearForMalformedCoverDate() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Author One");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        publication.setTitle("Paper One");
        publication.setAuthors(List.of("a1"));
        publication.setForum("f1");
        publication.setVolume("12");
        publication.setCoverDate("bad-date");

        Score publicationScore = new Score();
        publicationScore.setCoreRankingEquivalent("Q1");
        publicationScore.setScore(10.0);
        publicationScore.setAuthorScore(5.0);

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("i1")).thenReturn(Optional.of(indicator));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(forum));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(indicator)))
                .thenReturn(Map.of("Paper One", publicationScore));
        when(cacheService.getAuthorCache()).thenReturn(Map.of("a1", author));

        var result = facade.buildIndicatorWorkbookExport("user@uvt.ro", "i1");

        assertTrue(result.isPresent());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.get().workbookBytes()))) {
            var sheet = workbook.getSheet("Publications");
            assertEquals("", sheet.getRow(1).getCell(4).getStringCellValue());
        }
    }

    @Test
    void buildUserCnfisWorkbookExportPassesFilteredDataToWorkbookGenerator() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        ScholardexPublicationView pIn = new ScholardexPublicationView();
        pIn.setId("p-in");
        pIn.setCoverDate("2022-03-01");
        pIn.setForum("f1");
        pIn.setAuthors(List.of("a1"));

        ScholardexPublicationView pOut = new ScholardexPublicationView();
        pOut.setId("p-out");
        pOut.setCoverDate("2025-03-01");
        pOut.setForum("f1");
        pOut.setAuthors(List.of("a1"));

        Domain allDomain = new Domain();
        allDomain.setName("ALL");
        CNFISReport2025 report = new CNFISReport2025();

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(pIn, pOut));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(cnfiSScoringService2025.getReport(any(ScoringPublicationReadModel.class), eq(allDomain))).thenReturn(report);
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{7});

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        ArgumentCaptor<List<ScoringPublicationReadModel>> publicationCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<CNFISReport2025>> reportCaptor = ArgumentCaptor.forClass(List.class);
        verify(exportService).generateCNFISReportWorkbook(publicationCaptor.capture(), reportCaptor.capture(), anyMap(), eq(List.of("a1")), eq(false));
        assertEquals(1, publicationCaptor.getValue().size());
        assertEquals("p-in", publicationCaptor.getValue().getFirst().getId());
        assertEquals(1, reportCaptor.getValue().size());
    }

    @Test
    void buildUserCnfisWorkbookExportSkipsMalformedCoverDateWithoutCrash() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        ScholardexPublicationView valid = new ScholardexPublicationView();
        valid.setId("p-valid");
        valid.setCoverDate("2022-01-01");
        valid.setForum("f1");
        valid.setAuthors(List.of("a1"));

        ScholardexPublicationView invalid = new ScholardexPublicationView();
        invalid.setId("p-invalid");
        invalid.setCoverDate("20AB-99-99");
        invalid.setForum("f1");
        invalid.setAuthors(List.of("a1"));

        Domain allDomain = new Domain();
        allDomain.setName("ALL");
        CNFISReport2025 report = new CNFISReport2025();

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(valid, invalid));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(cnfiSScoringService2025.getReport(any(ScoringPublicationReadModel.class), eq(allDomain))).thenReturn(report);
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{7});

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        verify(publicationEnrichmentLinkerService).linkWosEnrichment(eq("p-valid"), any(), any(), any(), any(), any(), any());
        verify(scholardexProjectionReadService, never()).savePublicationView(any());

        ArgumentCaptor<List<ScoringPublicationReadModel>> publicationCaptor = ArgumentCaptor.forClass(List.class);
        verify(exportService).generateCNFISReportWorkbook(publicationCaptor.capture(), anyList(), anyMap(), eq(List.of("a1")), eq(false));
        assertEquals(1, publicationCaptor.getValue().size());
        assertEquals("p-valid", publicationCaptor.getValue().getFirst().getId());
    }

    @Test
    void buildIndicatorApplyViewCitationsMatchesReportScopedTotalForEquivalentScope() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        indicator.setId("ind-cit");
        indicator.setOutputType(Indicator.Type.CITATIONS);
        indicator.setSelector(Indicator.Selector.TOP_10);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);

        IndividualReport report = new IndividualReport();
        report.setId("rep-cit");
        report.setIndicators(List.of(indicator));
        report.setCriteria(List.of());
        var anyInstitution = new ro.uvt.pokedex.core.model.Institution();
        anyInstitution.setName("ANY");
        report.setIndividualAffiliation(anyInstitution);

        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Author One");

        ScholardexPublicationView cited = new ScholardexPublicationView();
        cited.setId("p1");
        cited.setTitle("Root Publication");
        cited.setAuthors(List.of("a1"));
        cited.setForum("f-root");

        List<ScholardexCitationView> citations = new java.util.ArrayList<>();
        List<ScholardexPublicationView> citingPublications = new java.util.ArrayList<>();
        List<String> citingIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String citingId = "cp-" + i;
            ScholardexCitationView citation = new ScholardexCitationView();
            citation.setCitedId("p1");
            citation.setCitingId(citingId);
            citations.add(citation);

            ScholardexPublicationView citing = new ScholardexPublicationView();
            citing.setId(citingId);
            citing.setTitle("Citing " + i);
            citing.setAuthors(List.of("a" + (i + 1)));
            citing.setForum("f-" + i);
            citingPublications.add(citing);
            citingIds.add(citingId);
        }

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("ind-cit")).thenReturn(Optional.of(indicator));
        when(individualReportRepository.findById("rep-cit")).thenReturn(Optional.of(report));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(cited));
        when(scholardexProjectionReadService.findAllCitationsByCitedIdIn(List.of("p1"))).thenReturn(citations);
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(citingIds)).thenReturn(citingPublications);
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());
        when(scientificProductionService.precomputeCitationBaseScores(anyList(), eq(indicator))).thenReturn(Map.of());
        when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), anyList(), eq(indicator), anyMap()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<ScoringPublicationReadModel> currentCiting = invocation.getArgument(1);
                    Map<String, Score> scores = new java.util.LinkedHashMap<>();
                    int value = 1;
                    for (ScoringPublicationReadModel publication : currentCiting) {
                        Score score = new Score();
                        score.setAuthorScore((double) value++);
                        score.setScore(1.0);
                        score.setQuarter("Q1");
                        scores.put(publication.getTitle(), score);
                    }
                    Score total = new Score();
                    total.setAuthorScore(currentCiting.size() * (currentCiting.size() + 1) / 2.0);
                    scores.put("total", total);
                    return scores;
                });

        UserIndicatorApplyViewModel applyView = facade.buildIndicatorApplyView("user@uvt.ro", "ind-cit");
        var reportComputationOpt = facade.computeReportScopedIndividualReport("user@uvt.ro", "rep-cit");

        assertEquals("user/indicators-apply", applyView.viewName());
        assertNotNull(reportComputationOpt.orElse(null));
        double applyTotal = Double.parseDouble(applyView.attributes().get("total").toString().replace(',', '.'));
        assertEquals(75.0, applyTotal);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Score>> scores = (Map<String, Map<String, Score>>) applyView.attributes().get("scores");
        assertNotNull(scores.get("Root Publication").get("total"));
        assertEquals(75.0, scores.get("Root Publication").get("total").getAuthorScore());
        @SuppressWarnings("unchecked")
        List<ScholardexPublicationView> visiblePublications = (List<ScholardexPublicationView>) applyView.attributes().get("publications");
        assertEquals(List.of(cited), visiblePublications);
        assertEquals(75.0, reportComputationOpt.orElseThrow().indicatorScoresByIndicatorId().get("ind-cit"));
    }

    @Test
    void buildIndicatorApplyViewUsesConfirmedPublicationsWhenAuthorLookupIsEmpty() {
        User user = new User();
        user.setEmail("user@uvt.ro");
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setScopusId(List.of("a1"));
        user.setResearcherProfile(profile);

        Indicator indicator = new Indicator();
        indicator.setId("ind-pub");
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);

        ScholardexPublicationView confirmed = new ScholardexPublicationView();
        confirmed.setId("p-confirmed");
        confirmed.setTitle("Confirmed Publication");
        confirmed.setAuthors(List.of("a1"));
        confirmed.setForum("f1");

        Score publicationScore = new Score();
        publicationScore.setAuthorScore(2.0);
        publicationScore.setScore(2.0);
        publicationScore.setQuarter("Q1");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("ind-pub")).thenReturn(Optional.of(indicator));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class))).thenReturn(List.of("a1"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(confirmed));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(indicator)))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of(
                        "Confirmed Publication", publicationScore,
                        "total", totalScore(2.0)
                )));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));

        UserIndicatorApplyViewModel applyView = facade.buildIndicatorApplyView("user@uvt.ro", "ind-pub");

        double total = Double.parseDouble(applyView.attributes().get("total").toString().replace(',', '.'));
        assertEquals(2.0, total);
        @SuppressWarnings("unchecked")
        List<ScholardexPublicationView> visiblePublications = (List<ScholardexPublicationView>) applyView.attributes().get("publications");
        assertEquals(List.of(confirmed), visiblePublications);
    }

    @Test
    void buildIndicatorApplyViewShowsWarningWhenNoConfirmedPublicationsExistForPublicationScoring() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        indicator.setId("ind-pub");
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        indicator.setScoringStrategy(Indicator.Strategy.GENERIC_COUNT);

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("ind-pub")).thenReturn(Optional.of(indicator));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author("a1", "Author One")));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of());
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(indicator)))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("total", totalScore(0.0))));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());

        UserIndicatorApplyViewModel applyView = facade.buildIndicatorApplyView("user@uvt.ro", "ind-pub");

        assertEquals(Boolean.TRUE, applyView.attributes().get("confirmedPublicationScoringWarning"));
    }

    @Test
    void buildIndicatorApplyViewDoesNotShowWarningForActivityIndicators() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        indicator.setId("ind-act");
        indicator.setOutputType(Indicator.Type.GENERIC_ACTIVITIES);
        var activity = new ro.uvt.pokedex.core.model.activities.Activity();
        activity.setName("Mentoring");
        indicator.setActivity(activity);

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("ind-act")).thenReturn(Optional.of(indicator));
        when(activityInstanceRepository.findAllByResearcherId("user@uvt.ro")).thenReturn(List.of());
        when(activityReportingService.calculateActivityScores(anyList(), eq(indicator)))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("total", totalScore(0.0))));

        UserIndicatorApplyViewModel applyView = facade.buildIndicatorApplyView("user@uvt.ro", "ind-act");

        assertEquals(null, applyView.attributes().get("confirmedPublicationScoringWarning"));
    }

    @Test
    void buildIndicatorWorkbookExportUsesConfirmedPublicationsForScoring() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);

        ScholardexAuthorView author = author("a1", "Author One");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        ScholardexPublicationView confirmed = new ScholardexPublicationView();
        confirmed.setId("p-confirmed");
        confirmed.setTitle("Confirmed Publication");
        confirmed.setAuthors(List.of("a1"));
        confirmed.setForum("f1");
        confirmed.setCoverDate("2023-01-01");

        Score publicationScore = new Score();
        publicationScore.setCoreRankingEquivalent("Q1");
        publicationScore.setScore(10.0);
        publicationScore.setAuthorScore(5.0);

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("i1")).thenReturn(Optional.of(indicator));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(confirmed));
        when(scholardexProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(forum));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(indicator)))
                .thenReturn(Map.of("Confirmed Publication", publicationScore));
        when(cacheService.getAuthorCache()).thenReturn(Map.of("a1", author));

        var result = facade.buildIndicatorWorkbookExport("user@uvt.ro", "i1");

        assertTrue(result.isPresent());
        verify(effectiveAuthorshipReadService).findConfirmedPublicationsForScoring("user@uvt.ro");
    }

    private static User userWithProfile(String email, List<String> scopusIds) {
        User user = new User();
        user.setEmail(email);
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setScopusId(scopusIds);
        user.setResearcherProfile(profile);
        return user;
    }

    private static Score totalScore(double authorScore) {
        Score total = new Score();
        total.setAuthorScore(authorScore);
        return total;
    }

    private static ScholardexForumView forum(String id) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(id);
        forum.setPublicationName(id);
        return forum;
    }

    private static ScholardexAuthorView author(String id, String name) {
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId(id);
        author.setName(name);
        return author;
    }
}
