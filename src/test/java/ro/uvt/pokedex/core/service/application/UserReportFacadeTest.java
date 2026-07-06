package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
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
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private IndicatorRepository indicatorRepository;
    @Mock
    private IndividualReportRepository individualReportRepository;
    @Mock
    private ReportVisibilityService reportVisibilityService;
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
    // Real instance (not a mock): getOrCompute must pass through to the supplier for every test.
    @org.mockito.Spy
    private ReportingLookupMemoization reportingLookupMemoization = new ReportingLookupMemoization();

    @InjectMocks
    private UserReportFacade facade;

    @BeforeEach
    void setUpLookupService() {
        lenient().when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(User.ResearcherProfile.class)))
                .thenAnswer(invocation -> {
                    User.ResearcherProfile profile = invocation.getArgument(0);
                    return profile.getScopusId() == null ? List.of() : profile.getScopusId();
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
    void buildIndividualReportsListViewReturnsOnlyReportsVisibleToTheUser() {
        IndividualReport report = new IndividualReport();
        report.setTitle("R1");
        when(reportVisibilityService.listVisibleReportsForUser("user@uvt.ro"))
                .thenReturn(List.of(report));

        var vm = facade.buildIndividualReportsListView("user@uvt.ro");

        assertEquals(1, vm.individualReports().size());
        assertEquals("R1", vm.individualReports().getFirst().getTitle());
    }

    @Test
    void buildIndividualReportsListViewFallsBackToCatalogWhenNoUserEmail() {
        // Platform admins or anonymous contexts hit the fallback so they see something instead
        // of an empty list. Belt-and-suspenders: also covers null/blank usernames.
        IndividualReport report = new IndividualReport();
        report.setTitle("Catalog report");
        when(individualReportRepository.findAll()).thenReturn(List.of(report));

        var vm = facade.buildIndividualReportsListView(null);

        assertEquals(1, vm.individualReports().size());
        assertEquals("Catalog report", vm.individualReports().getFirst().getTitle());
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
    void buildIndicatorApplyViewReturnsBaseViewWhenUserMissingOrProfileMissing() {
        when(userService.getUserByEmail("missing@uvt.ro")).thenReturn(Optional.empty());
        User noProfile = new User();
        noProfile.setEmail("nop@uvt.ro");
        when(userService.getUserByEmail("nop@uvt.ro")).thenReturn(Optional.of(noProfile));

        UserIndicatorApplyViewModel missingUser = facade.buildIndicatorApplyView("missing@uvt.ro", "i1");
        UserIndicatorApplyViewModel missingProfile = facade.buildIndicatorApplyView("nop@uvt.ro", "i1");

        assertEquals("user/indicators", missingUser.viewName());
        assertEquals("user/indicators", missingProfile.viewName());
    }

    @Test
    void buildIndicatorApplyViewReturnsBaseViewWhenIndicatorMissing() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("missing")).thenReturn(Optional.empty());

        UserIndicatorApplyViewModel applyView = facade.buildIndicatorApplyView("user@uvt.ro", "missing");

        assertEquals("user/indicators", applyView.viewName());
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
    void buildLegacyUserCnfisWorkbookExportReturnsNotFoundWhenProfileMissing() throws Exception {
        User user = new User();
        user.setEmail("user@uvt.ro");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));

        var result = facade.buildLegacyUserCnfisWorkbookExport("user@uvt.ro");

        assertEquals(UserWorkbookExportStatus.NOT_FOUND, result.status());
    }

    @Test
    void buildLegacyUserCnfisWorkbookExportWritesRowsWhenTemplateExists() throws Exception {
        ensureLegacyTemplateFixturePresent();

        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        ScholardexAuthorView author = author("a1", "Author One");

        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId("p1");
        publication.setTitle("Legacy Paper");
        publication.setDoi("10.1/legacy");
        publication.setForum("f1");
        publication.setCoverDate("2023-01-01");
        publication.setAuthors(List.of("a1", "a2"));

        ScholardexForumView forum = forum("f1");
        forum.setPublicationName("Legacy Forum");
        forum.setEIssn("1111-1111");
        forum.setIssn("2222-2222");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));

        var result = facade.buildLegacyUserCnfisWorkbookExport("user@uvt.ro");

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.workbookBytes()))) {
            var sheet = workbook.getSheetAt(0);
            var row = sheet.getRow(15);
            assertEquals("2023", row.getCell(0).getStringCellValue());
            assertEquals("Legacy Paper", row.getCell(1).getStringCellValue());
            assertEquals("10.1/legacy", row.getCell(2).getStringCellValue());
            assertEquals("Legacy Forum", row.getCell(5).getStringCellValue());
            assertEquals("1111-1111", row.getCell(6).getStringCellValue());
            assertEquals("2222-2222", row.getCell(7).getStringCellValue());
            assertEquals(2, (int) row.getCell(13).getNumericCellValue());
            assertEquals(1, (int) row.getCell(14).getNumericCellValue());
        }
    }

    @Test
    void buildIndicatorWorkbookExportPublicationsContainsExpectedHeadersAndRow() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS");

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
            assertEquals("Forum", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("Volume", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("Year", sheet.getRow(0).getCell(4).getStringCellValue());
            assertEquals("Workshop", sheet.getRow(0).getCell(5).getStringCellValue());
            assertEquals("Category", sheet.getRow(0).getCell(6).getStringCellValue());
            assertEquals("Forum Score", sheet.getRow(0).getCell(7).getStringCellValue());
            assertEquals("Author Score", sheet.getRow(0).getCell(8).getStringCellValue());
            assertEquals("Paper One", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Author One", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Forum One", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("12", sheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals("2023", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("No", sheet.getRow(1).getCell(5).getStringCellValue());
            assertEquals("Q1", sheet.getRow(1).getCell(6).getStringCellValue());
            assertEquals(10.0, sheet.getRow(1).getCell(7).getNumericCellValue());
            assertEquals(5.0, sheet.getRow(1).getCell(8).getNumericCellValue());
        }
    }

    @Test
    void buildIndicatorWorkbookExportPublicationsUsesBlankYearForMalformedCoverDate() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS");

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
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");

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
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");

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
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(User.ResearcherProfile.class))).thenReturn(List.of("a1"));
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
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");

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
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "GENERIC_ACTIVITIES");
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
    void buildIndicatorApplyViewMainAuthorDoesNotCrashOnPublicationWithNoAuthors() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator indicator = new Indicator();
        indicator.setId("ind-main-safe");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS_MAIN_AUTHOR");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");

        ScholardexPublicationView emptyAuthors = new ScholardexPublicationView();
        emptyAuthors.setId("p-empty");
        emptyAuthors.setTitle("Empty");
        emptyAuthors.setAuthors(List.of());
        emptyAuthors.setForum("f1");

        ScholardexPublicationView normal = new ScholardexPublicationView();
        normal.setId("p-ok");
        normal.setTitle("OK");
        normal.setAuthors(List.of("a1"));
        normal.setForum("f1");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("ind-main-safe")).thenReturn(Optional.of(indicator));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro"))
                .thenReturn(List.of(emptyAuthors, normal));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(indicator)))
                .thenReturn(new LinkedHashMap<>(Map.of("OK", totalScore(1.0), "total", totalScore(1.0))));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));

        UserIndicatorApplyViewModel applyView = facade.buildIndicatorApplyView("user@uvt.ro", "ind-main-safe");
        assertEquals("user/indicators-apply", applyView.viewName());
    }

    @Test
    void buildIndicatorWorkbookExportUsesConfirmedPublicationsForScoring() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS");

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

    @Test
    void buildIndicatorWorkbookExportCitationsExcludeSelfWritesOnlyExternalRows() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS_EXCLUDE_SELF");

        ScholardexAuthorView author = author("a1", "Author One");
        ScholardexAuthorView external = author("a2", "Author Two");

        ScholardexPublicationView cited = new ScholardexPublicationView();
        cited.setId("p1");
        cited.setTitle("Root");
        cited.setAuthors(List.of("a1"));
        cited.setForum("f-root");

        ScholardexCitationView selfCitation = new ScholardexCitationView();
        selfCitation.setCitedId("p1");
        selfCitation.setCitingId("cp-self");
        ScholardexCitationView noForumCitation = new ScholardexCitationView();
        noForumCitation.setCitedId("p1");
        noForumCitation.setCitingId("cp-noforum");
        ScholardexCitationView okCitation = new ScholardexCitationView();
        okCitation.setCitedId("p1");
        okCitation.setCitingId("cp-ok");

        ScholardexPublicationView cpSelf = new ScholardexPublicationView();
        cpSelf.setId("cp-self");
        cpSelf.setTitle("Citing Self");
        cpSelf.setAuthors(List.of("a1"));
        cpSelf.setForum("f-self");
        cpSelf.setCoverDate("2024-01-01");

        ScholardexPublicationView cpNoForum = new ScholardexPublicationView();
        cpNoForum.setId("cp-noforum");
        cpNoForum.setTitle("Citing NoForum");
        cpNoForum.setAuthors(List.of("a2"));
        cpNoForum.setForum("f-missing");
        cpNoForum.setCoverDate("2024-01-01");

        ScholardexPublicationView cpOk = new ScholardexPublicationView();
        cpOk.setId("cp-ok");
        cpOk.setTitle("Citing OK");
        cpOk.setAuthors(List.of("a2"));
        cpOk.setForum("f-ok");
        cpOk.setCoverDate("2024-01-01");

        ScholardexForumView rootForum = forum("f-root");
        ScholardexForumView okForum = forum("f-ok");
        okForum.setPublicationName("Forum OK");

        Score citationScore = new Score();
        citationScore.setAuthorScore(1.0);
        citationScore.setScore(1.0);
        citationScore.setCoreRankingEquivalent("Q1");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("i-cit")).thenReturn(Optional.of(indicator));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(cited));
        when(scholardexProjectionReadService.findAllCitationsByCitedId("p1"))
                .thenReturn(List.of(selfCitation, noForumCitation, okCitation));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("cp-self", "cp-noforum", "cp-ok")))
                .thenReturn(List.of(cpSelf, cpNoForum, cpOk));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection()))
                .thenReturn(List.of(rootForum, okForum));
        when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), anyList(), eq(indicator)))
                .thenReturn(Map.of("Citing OK", citationScore));
        when(cacheService.getAuthorCache()).thenReturn(Map.of("a1", author, "a2", external));

        var result = facade.buildIndicatorWorkbookExport("user@uvt.ro", "i-cit");

        assertTrue(result.isPresent());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.get().workbookBytes()))) {
            var sheet = workbook.getSheet("Citations");
            List<String> citingTitles = new ArrayList<>();
            int nonEmptyDataRows = 0;
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                if (row == null || row.getCell(1) == null) {
                    continue;
                }
                citingTitles.add(row.getCell(1).getStringCellValue());
                if (i > 0 && !row.getCell(1).getStringCellValue().isBlank()) {
                    nonEmptyDataRows++;
                }
            }
            assertTrue(citingTitles.contains("Citing OK"));
            assertFalse(citingTitles.contains("Citing Self"));
            assertFalse(citingTitles.contains("Citing NoForum"));
            assertEquals(1, nonEmptyDataRows);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                if (row != null && row.getCell(1) != null && "Citing OK".equals(row.getCell(1).getStringCellValue())) {
                    assertEquals("Root", row.getCell(0).getStringCellValue());
                    assertEquals("Forum OK", row.getCell(3).getStringCellValue());
                    assertEquals("Q1", row.getCell(7).getStringCellValue());
                    assertEquals(1.0, row.getCell(9).getNumericCellValue());
                }
            }
        }
    }

    @Test
    void computeProvisionalReportScoresFromDeclaredAuthorsWithoutAUserOrConfirmedDecisions() {
        // H77: the DECLARED path scores a report from resolved canonical author ids — every publication attributed to
        // those authors — with NO User, NO confirmed PublicationAuthorshipDecision, and NO activity instances.
        Indicator indicator = new Indicator();
        indicator.setId("ind-pub");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");

        IndividualReport report = new IndividualReport();
        report.setId("rep-prov");
        report.setIndicators(List.of(indicator));
        report.setCriteria(List.of());
        var anyInstitution = new ro.uvt.pokedex.core.model.Institution();
        anyInstitution.setName("ANY");
        report.setIndividualAffiliation(anyInstitution);

        ScholardexPublicationView declared = new ScholardexPublicationView();
        declared.setId("p1");
        declared.setTitle("Declared Paper");
        declared.setAuthors(List.of("a1"));
        declared.setForum("f1");

        when(individualReportRepository.findById("rep-prov")).thenReturn(Optional.of(report));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(declared));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(indicator)))
                .thenReturn(new LinkedHashMap<>(Map.of("Declared Paper", totalScore(3.0), "total", totalScore(3.0))));

        var computationOpt = facade.computeProvisionalReport(List.of("a1"), "rep-prov", 2024);

        assertTrue(computationOpt.isPresent());
        assertEquals(3.0, computationOpt.orElseThrow().indicatorScoresByIndicatorId().get("ind-pub"));
        verify(userService, never()).getUserByEmail(any());
        verify(effectiveAuthorshipReadService, never()).findConfirmedPublicationsForScoring(any());
    }

    @Test
    void computeProvisionalReportReturnsEmptyWhenNoResolvedAuthors() {
        // No resolved authors → nothing to score; the provisional path short-circuits to empty.
        assertTrue(facade.computeProvisionalReport(List.of(), "rep-prov", 2024).isEmpty());
    }

    @Test
    void hIndexIndicatorScoresItsHInTheReportRollUpNotZero() {
        // H67: an aggregate Hirsch indicator must contribute its h to the report roll-up (indicatorScoresByIndicatorId),
        // not 0. Before this, scoreReport ignored isHIndexOutput() so the headline score was 0 while the drill-down
        // detail showed the real h.
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator indicator = new Indicator();
        indicator.setId("ind-h");
        indicator.setKind(new ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind.HIndex(
                ro.uvt.pokedex.core.model.reporting.scoring.HIndexSource.SCHOLARDEX, false));

        IndividualReport report = new IndividualReport();
        report.setId("rep-h");
        report.setIndicators(List.of(indicator));
        report.setCriteria(List.of());
        var anyInstitution = new ro.uvt.pokedex.core.model.Institution();
        anyInstitution.setName("ANY");
        report.setIndividualAffiliation(anyInstitution);

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(individualReportRepository.findById("rep-h")).thenReturn(Optional.of(report));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro"))
                .thenReturn(List.of(pubWithCites("p1", 5), pubWithCites("p2", 3), pubWithCites("p3", 1)));

        var computation = facade.computeReportScopedIndividualReport("user@uvt.ro", "rep-h");

        assertTrue(computation.isPresent());
        // citedByCount [5,3,1] → Scholardex h = 2 (two pubs with ≥2 citations).
        assertEquals(2.0, computation.orElseThrow().indicatorScoresByIndicatorId().get("ind-h"));
    }

    @Test
    void buildIndicatorApplyViewForHIndexReturnsTheComputedH() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator indicator = new Indicator();
        indicator.setId("ind-h");
        indicator.setKind(new ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind.HIndex(
                ro.uvt.pokedex.core.model.reporting.scoring.HIndexSource.SCHOLARDEX, false));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("ind-h")).thenReturn(Optional.of(indicator));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro"))
                .thenReturn(List.of(pubWithCites("p1", 5), pubWithCites("p2", 3), pubWithCites("p3", 1)));

        UserIndicatorApplyViewModel view = facade.buildIndicatorApplyView("user@uvt.ro", "ind-h");

        assertEquals("user/indicators-apply", view.viewName());
        assertEquals("hindex", view.attributes().get("outputMode"));
        assertEquals("2", view.attributes().get("total"));
    }

    private static ScholardexPublicationView pubWithCites(String id, int citedByCount) {
        ScholardexPublicationView p = new ScholardexPublicationView();
        p.setId(id);
        p.setCitedByCount(citedByCount);
        return p;
    }

    @Test
    void buildReportScopedIndicatorDetailReturnsActivityModeWithFilteredActivities() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator indicator = new Indicator();
        indicator.setId("ind-act");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "GENERIC_ACTIVITIES");
        var activity = new ro.uvt.pokedex.core.model.activities.Activity();
        activity.setName("Mentoring");
        indicator.setActivity(activity);

        IndividualReport report = new IndividualReport();
        report.setId("rep-act");
        report.setIndicators(List.of(indicator));

        var match = new ro.uvt.pokedex.core.model.activities.ActivityInstance();
        match.setActivity(activity);
        var other = new ro.uvt.pokedex.core.model.activities.ActivityInstance();
        var otherActivity = new ro.uvt.pokedex.core.model.activities.Activity();
        otherActivity.setName("Other");
        other.setActivity(otherActivity);

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(individualReportRepository.findById("rep-act")).thenReturn(Optional.of(report));
        when(activityInstanceRepository.findAllByResearcherId("user@uvt.ro")).thenReturn(List.of(match, other));
        when(activityReportingService.calculateActivityScores(anyList(), eq(indicator)))
                .thenReturn(new LinkedHashMap<>(Map.of("total", totalScore(2.0), "Mentoring", totalScore(2.0))));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of());

        var detailOpt = facade.buildReportScopedIndicatorDetail("user@uvt.ro", "rep-act", "ind-act");

        assertTrue(detailOpt.isPresent());
        var detail = detailOpt.orElseThrow();
        assertEquals("user/indicators-apply", detail.viewName());
        assertEquals(2.0, detail.summary().totalScore());
        assertEquals("activities", detail.rawGraph().get("outputMode"));
    }

    @Test
    void buildReportScopedIndicatorDetailReturnsEmptyWhenIndicatorIdIsNull() {
        assertTrue(facade.buildReportScopedIndicatorDetail("user@uvt.ro", "rep-null-id", null).isEmpty());
    }

    @Test
    void buildReportScopedIndicatorDetailReturnsEmptyWhenUserOrReportOrIndicatorMissing() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        IndividualReport report = new IndividualReport();
        report.setId("rep-missing-ind");
        report.setIndicators(List.of());

        when(userService.getUserByEmail("missing@uvt.ro")).thenReturn(Optional.empty());
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(individualReportRepository.findById("rep-404")).thenReturn(Optional.empty());
        when(individualReportRepository.findById("rep-missing-ind")).thenReturn(Optional.of(report));

        assertTrue(facade.buildReportScopedIndicatorDetail("missing@uvt.ro", "rep-missing-ind", "x").isEmpty());
        assertTrue(facade.buildReportScopedIndicatorDetail("user@uvt.ro", "rep-404", "x").isEmpty());
        assertTrue(facade.buildReportScopedIndicatorDetail("user@uvt.ro", "rep-missing-ind", "x").isEmpty());
    }

    @Test
    void buildReportScopedIndicatorDetailReturnsPublicationModesForMainAndCoauthor() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator main = new Indicator();
        main.setId("ind-main");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(main, "PUBLICATIONS_MAIN_AUTHOR");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(main, "GENERIC_COUNT");
        Indicator co = new Indicator();
        co.setId("ind-co");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(co, "PUBLICATIONS_COAUTHOR");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(co, "GENERIC_COUNT");

        IndividualReport report = new IndividualReport();
        report.setId("rep-pub");
        report.setIndicators(List.of(main, co));

        ScholardexPublicationView pMain = new ScholardexPublicationView();
        pMain.setId("p1");
        pMain.setTitle("Main");
        pMain.setAuthors(List.of("a1", "a2"));
        ScholardexPublicationView pCo = new ScholardexPublicationView();
        pCo.setId("p2");
        pCo.setTitle("Co");
        pCo.setAuthors(List.of("x", "a1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(individualReportRepository.findById("rep-pub")).thenReturn(Optional.of(report));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(pMain, pCo));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), any(Indicator.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<ScoringPublicationReadModel> pubs = invocation.getArgument(0);
                    Indicator ind = invocation.getArgument(1);
                    if (ind.getOutputType() == "PUBLICATIONS_MAIN_AUTHOR") {
                        return new LinkedHashMap<>(Map.of("Main", totalScore(2.0), "total", totalScore(2.0)));
                    }
                    return new LinkedHashMap<>(Map.of("Co", totalScore(1.0), "total", totalScore(1.0)));
                });

        var mainDetail = facade.buildReportScopedIndicatorDetail("user@uvt.ro", "rep-pub", "ind-main").orElseThrow();
        var coDetail = facade.buildReportScopedIndicatorDetail("user@uvt.ro", "rep-pub", "ind-co").orElseThrow();

        assertEquals("publications", mainDetail.rawGraph().get("outputMode"));
        assertEquals("publications", coDetail.rawGraph().get("outputMode"));
        assertEquals(2.0, mainDetail.summary().totalScore());
        assertEquals(1.0, coDetail.summary().totalScore());
    }

    @Test
    void buildReportScopedIndicatorDetailFirstOrCorrespondingFiltersLikeTheRollUp() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator indicator = new Indicator();
        indicator.setId("ind-foc");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "PUBLICATIONS_FIRST_OR_CORRESPONDING");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");

        IndividualReport report = new IndividualReport();
        report.setId("rep-foc");
        report.setIndicators(List.of(indicator));

        ScholardexPublicationView first = new ScholardexPublicationView();
        first.setId("p-first");
        first.setTitle("First");
        first.setAuthors(List.of("a1", "x"));
        ScholardexPublicationView corresponding = new ScholardexPublicationView();
        corresponding.setId("p-corr");
        corresponding.setTitle("Corresponding");
        corresponding.setAuthors(List.of("x", "a1"));
        corresponding.setCorrespondingAuthorIds(List.of("a1"));
        ScholardexPublicationView neither = new ScholardexPublicationView();
        neither.setId("p-neither");
        neither.setTitle("Neither");
        neither.setAuthors(List.of("x", "a1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(individualReportRepository.findById("rep-foc")).thenReturn(Optional.of(report));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro"))
                .thenReturn(List.of(first, corresponding, neither));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(indicator)))
                .thenAnswer(invocation -> {
                    List<?> pubs = invocation.getArgument(0);
                    LinkedHashMap<String, Score> scores = new LinkedHashMap<>();
                    pubs.forEach(p -> scores.put(((ScoringPublicationReadModel) p).getTitle(), totalScore(1.0)));
                    scores.put("total", totalScore(pubs.size()));
                    return scores;
                });

        var detail = facade.buildReportScopedIndicatorDetail("user@uvt.ro", "rep-foc", "ind-foc").orElseThrow();

        // The detail must score/list exactly the roll-up's publication set: first-author + corresponding, not "Neither".
        @SuppressWarnings("unchecked")
        List<ScholardexPublicationView> listed = (List<ScholardexPublicationView>) detail.rawGraph().get("publications");
        assertEquals(List.of("p-first", "p-corr"), listed.stream().map(ScholardexPublicationView::getId).toList());
        assertEquals(2.0, detail.summary().totalScore());
    }

    @Test
    void buildReportScopedIndicatorDetailMainAuthorDoesNotCrashOnPublicationWithNoAuthors() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator main = new Indicator();
        main.setId("ind-main-detail-safe");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(main, "PUBLICATIONS_MAIN_AUTHOR");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(main, "GENERIC_COUNT");

        IndividualReport report = new IndividualReport();
        report.setId("rep-main-safe");
        report.setIndicators(List.of(main));

        ScholardexPublicationView emptyAuthors = new ScholardexPublicationView();
        emptyAuthors.setId("p-empty");
        emptyAuthors.setTitle("Empty");
        emptyAuthors.setAuthors(List.of());
        ScholardexPublicationView normal = new ScholardexPublicationView();
        normal.setId("p-ok");
        normal.setTitle("OK");
        normal.setAuthors(List.of("a1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(individualReportRepository.findById("rep-main-safe")).thenReturn(Optional.of(report));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro"))
                .thenReturn(List.of(emptyAuthors, normal));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(main)))
                .thenReturn(new LinkedHashMap<>(Map.of("OK", totalScore(1.0), "total", totalScore(1.0))));

        var detail = facade.buildReportScopedIndicatorDetail("user@uvt.ro", "rep-main-safe", "ind-main-detail-safe");
        assertTrue(detail.isPresent());
    }

    @Test
    void buildReportScopedIndicatorDetailAppliesAffiliationFilterForCitationMode() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator indicator = new Indicator();
        indicator.setId("ind-cit-aff");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");

        var inst = new ro.uvt.pokedex.core.model.Institution();
        inst.setName("INST");
        var scAff = new ScholardexAffiliationView();
        scAff.setAfid("afid-1");
        inst.setScopusAffiliations(List.of(scAff));

        IndividualReport report = new IndividualReport();
        report.setId("rep-cit-aff");
        report.setIndicators(List.of(indicator));
        report.setIndividualAffiliation(inst);

        ScholardexPublicationView kept = new ScholardexPublicationView();
        kept.setId("p-kept");
        kept.setTitle("Kept");
        kept.setAuthors(List.of("a1"));
        kept.setAffiliations(List.of("afid-1"));
        kept.setForum("f1");
        ScholardexPublicationView filtered = new ScholardexPublicationView();
        filtered.setId("p-drop");
        filtered.setTitle("Drop");
        filtered.setAuthors(List.of("a1"));
        filtered.setAffiliations(List.of("other"));
        filtered.setForum("f2");

        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setCitedId("p-kept");
        citation.setCitingId("cp-1");
        ScholardexPublicationView citing = new ScholardexPublicationView();
        citing.setId("cp-1");
        citing.setTitle("Citing");
        citing.setAuthors(List.of("a2"));
        citing.setForum("f3");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(individualReportRepository.findById("rep-cit-aff")).thenReturn(Optional.of(report));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro"))
                .thenReturn(List.of(kept, filtered));
        when(scholardexProjectionReadService.findAllCitationsByCitedIdIn(List.of("p-kept"))).thenReturn(List.of(citation));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("cp-1"))).thenReturn(List.of(citing));
        lenient().when(scientificProductionService.precomputeCitationBaseScores(anyList(), eq(indicator))).thenReturn(Map.of());
        lenient().when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), anyList(), eq(indicator), anyMap()))
                .thenReturn(Map.of("Citing", totalScore(3.0), "total", totalScore(3.0)));
        lenient().when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());

        var detail = facade.buildReportScopedIndicatorDetail("user@uvt.ro", "rep-cit-aff", "ind-cit-aff").orElseThrow();

        verify(scholardexProjectionReadService).findAllCitationsByCitedIdIn(List.of("p-kept"));
        assertEquals(3.0, detail.summary().totalScore());
        assertEquals("citations", detail.rawGraph().get("outputMode"));
    }

    @Test
    void computeReportScopedIndividualReportHandlesNullIndicatorsAndInvalidCriterionIndices() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator valid = new Indicator();
        valid.setId("ind-valid");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(valid, "PUBLICATIONS");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(valid, "GENERIC_COUNT");

        var criterion = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        criterion.setIndicatorIndices(new ArrayList<>(java.util.Arrays.asList(-1, 1, 99, null)));

        IndividualReport report = new IndividualReport();
        report.setId("rep-crit");
        report.setIndicators(new ArrayList<>(java.util.Arrays.asList(valid, null)));
        report.setCriteria(List.of(criterion));

        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("p1");
        pub.setTitle("P1");
        pub.setAuthors(List.of("a1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(individualReportRepository.findById("rep-crit")).thenReturn(Optional.of(report));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(pub));
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(valid)))
                .thenReturn(Map.of("total", totalScore(2.0)));

        var computation = facade.computeReportScopedIndividualReport("user@uvt.ro", "rep-crit").orElseThrow();
        assertEquals(0.0, computation.criterionScores().get(0));
    }

    @Test
    void buildIndicatorApplyViewCitationsHandlesDuplicateCitedTitlesWithoutDroppingValidScores() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator indicator = new Indicator();
        indicator.setId("ind-cit-dup");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");

        ScholardexAuthorView author = author("a1", "Author One");

        ScholardexPublicationView p1 = new ScholardexPublicationView();
        p1.setId("p1");
        p1.setTitle("Same");
        p1.setAuthors(List.of("a1"));
        p1.setForum("f1");
        ScholardexPublicationView p2 = new ScholardexPublicationView();
        p2.setId("p2");
        p2.setTitle("Same");
        p2.setAuthors(List.of("a1"));
        p2.setForum("f2");

        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setCitedId("p1");
        citation.setCitingId("cp1");

        ScholardexPublicationView citing = new ScholardexPublicationView();
        citing.setId("cp1");
        citing.setTitle("Citing");
        citing.setAuthors(List.of("x"));
        citing.setForum("f3");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("ind-cit-dup")).thenReturn(Optional.of(indicator));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(p1, p2));
        when(scholardexProjectionReadService.findAllCitationsByCitedIdIn(List.of("p1", "p2"))).thenReturn(List.of(citation));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("cp1"))).thenReturn(List.of(citing));
        when(scientificProductionService.precomputeCitationBaseScores(anyList(), eq(indicator))).thenReturn(Map.of());
        when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), anyList(), eq(indicator), anyMap()))
                .thenAnswer(invocation -> {
                    ScoringPublicationReadModel cited = invocation.getArgument(0);
                    if ("p1".equals(cited.getId())) {
                        return Map.of("Citing", totalScore(2.0), "total", totalScore(2.0));
                    }
                    return Map.of("total", totalScore(0.0));
                });
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());

        UserIndicatorApplyViewModel applyView = facade.buildIndicatorApplyView("user@uvt.ro", "ind-cit-dup");
        @SuppressWarnings("unchecked")
        List<ScholardexPublicationView> visible = (List<ScholardexPublicationView>) applyView.attributes().get("publications");
        assertFalse(visible.isEmpty());
    }

    @Test
    void buildIndicatorApplyViewCitationsBuildsForumWosLinkMapFromIssnCandidates() {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));

        Indicator indicator = new Indicator();
        indicator.setId("ind-cit-wos");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");

        ScholardexAuthorView author = author("a1", "Author One");

        ScholardexPublicationView cited = new ScholardexPublicationView();
        cited.setId("p1");
        cited.setTitle("Root Publication");
        cited.setAuthors(List.of("a1"));
        cited.setForum("f-root");

        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setCitedId("p1");
        citation.setCitingId("cp-1");

        ScholardexPublicationView citing = new ScholardexPublicationView();
        citing.setId("cp-1");
        citing.setTitle("Citing 1");
        citing.setAuthors(List.of("a2"));
        citing.setForum("f-issn");

        ScholardexForumView issnForum = forum("f-issn");
        issnForum.setIssn("ISSN 1234-567X");
        issnForum.setEIssn("invalid");

        Score citationScore = new Score();
        citationScore.setAuthorScore(3.0);
        citationScore.setScore(3.0);
        citationScore.setQuarter("Q1");

        WoSRanking ranking = new WoSRanking();
        ranking.setId("WOS-123");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("ind-cit-wos")).thenReturn(Optional.of(indicator));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(cited));
        when(scholardexProjectionReadService.findAllCitationsByCitedIdIn(List.of("p1"))).thenReturn(List.of(citation));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("cp-1"))).thenReturn(List.of(citing));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(issnForum));
        when(scientificProductionService.precomputeCitationBaseScores(anyList(), eq(indicator))).thenReturn(Map.of());
        when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), anyList(), eq(indicator), anyMap()))
                .thenReturn(Map.of("Citing 1", citationScore, "total", totalScore(3.0)));
        when(reportingLookupPort.getRankingsByIssn("1234567X")).thenReturn(List.of(ranking));

        UserIndicatorApplyViewModel applyView = facade.buildIndicatorApplyView("user@uvt.ro", "ind-cit-wos");

        @SuppressWarnings("unchecked")
        Map<String, String> forumWosLinkMap = (Map<String, String>) applyView.attributes().get("forumWosLinkMap");
        assertEquals("WOS-123", forumWosLinkMap.get("f-issn"));
    }

    @Test
    void applyFinalSelectorDelegatesToSupport() throws Exception {
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");

        Map<String, Score> scores = new LinkedHashMap<>();
        for (int i = 1; i <= 12; i++) {
            Score score = new Score();
            score.setAuthorScore(i);
            score.setScore(i);
            scores.put("p" + i, score);
        }
        scores.put("total", totalScore(0.0));

        Map<String, Map<String, Score>> nested = new HashMap<>();
        nested.put("root", scores);

        Method method = UserReportFacade.class.getDeclaredMethod("applyFinalSelector", Indicator.class, Map.class);
        method.setAccessible(true);
        method.invoke(facade, indicator, nested);

        assertEquals(11, nested.get("root").size());
        assertFalse(nested.get("root").containsKey("p1"));
        assertTrue(nested.get("root").containsKey("p12"));
    }

    @Test
    void buildIndividualReportViewReturnsRedirectWhenComputationCannotBeBuilt() {
        IndividualReport report = new IndividualReport();
        report.setId("rep1");
        report.setIndicators(List.of());
        report.setCriteria(List.of());
        report.setIndividualAffiliation(null);

        when(individualReportRepository.findById("rep1")).thenReturn(Optional.of(report));
        when(userService.getUserByEmail("missing@uvt.ro")).thenReturn(Optional.empty());

        var vm = facade.buildIndividualReportView("missing@uvt.ro", "rep1");

        assertEquals("redirect:/error", vm.redirect());
    }

    @Test
    void reportUsesPublicationScoringCoversNullAndPositiveCases() {
        when(individualReportRepository.findById("missing")).thenReturn(Optional.empty());
        assertFalse(facade.reportUsesPublicationScoring("missing"));

        IndividualReport noIndicators = new IndividualReport();
        noIndicators.setId("r0");
        noIndicators.setIndicators(null);
        when(individualReportRepository.findById("r0")).thenReturn(Optional.of(noIndicators));
        assertFalse(facade.reportUsesPublicationScoring("r0"));

        Indicator publications = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(publications, "PUBLICATIONS");
        IndividualReport withPublication = new IndividualReport();
        withPublication.setId("r1");
        withPublication.setIndicators(List.of(publications));
        when(individualReportRepository.findById("r1")).thenReturn(Optional.of(withPublication));
        assertTrue(facade.reportUsesPublicationScoring("r1"));
    }

    @Test
    void passthroughPublicMethodsDelegateToDependencies() {
        Indicator indicator = new Indicator();
        indicator.setId("i1");
        IndividualReport report = new IndividualReport();
        report.setId("r1");
        when(indicatorRepository.findById("i1")).thenReturn(Optional.of(indicator));
        when(individualReportRepository.findById("r1")).thenReturn(Optional.of(report));
        when(effectiveAuthorshipReadService.hasConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(true);

        assertTrue(facade.findIndicatorById("i1").isPresent());
        assertTrue(facade.findIndividualReportById("r1").isPresent());
        assertTrue(facade.hasConfirmedPublicationsForScoring("user@uvt.ro"));
    }

    @Test
    void privateComputationHelpersAreReachable() throws Exception {
        Indicator publicationIndicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(publicationIndicator, "PUBLICATIONS");
        when(scientificProductionService.calculateScientificProductionScore(anyList(), eq(publicationIndicator)))
                .thenReturn(Map.of("total", totalScore(4.0)));

        Indicator citationIndicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(citationIndicator, "CITATIONS");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(citationIndicator, "GENERIC_COUNT");
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("p1");
        pub.setTitle("P1");
        pub.setAuthors(List.of("a1"));
        ScholardexAuthorView author = author("a1", "A1");
        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setCitedId("p1");
        citation.setCitingId("cp1");
        ScholardexPublicationView citing = new ScholardexPublicationView();
        citing.setId("cp1");
        citing.setTitle("CP1");
        citing.setAuthors(List.of("x"));
        when(scholardexProjectionReadService.findAllCitationsByCitedIdIn(List.of("p1"))).thenReturn(List.of(citation));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("cp1"))).thenReturn(List.of(citing));
        when(scientificProductionService.precomputeCitationBaseScores(anyList(), eq(citationIndicator))).thenReturn(Map.of());
        when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), anyList(), eq(citationIndicator), anyMap()))
                .thenReturn(Map.of("CP1", totalScore(2.0), "total", totalScore(2.0)));

        double publicationScore = (double) invokePrivate(
                "calculatePublicationScore",
                new Class[]{Indicator.class, List.class, List.class},
                publicationIndicator, List.of(author), List.of(pub));
        double citationScore = (double) invokePrivate(
                "calculateCitationScore",
                new Class[]{Indicator.class, List.class, List.class},
                citationIndicator, List.of(author), List.of(pub));

        assertEquals(4.0, publicationScore);
        assertEquals(2.0, citationScore);
    }

    @Test
    void citationWorkbookWritesAllExpectedHeaderCells() throws Exception {
        User user = userWithProfile("user@uvt.ro", List.of("a1"));
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS");

        ScholardexAuthorView author = author("a1", "Author One");
        ScholardexAuthorView external = author("a2", "Author Two");

        ScholardexPublicationView cited = new ScholardexPublicationView();
        cited.setId("p1");
        cited.setTitle("Root");
        cited.setAuthors(List.of("a1"));
        cited.setForum("f-root");

        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setCitedId("p1");
        citation.setCitingId("cp-ok");

        ScholardexPublicationView cpOk = new ScholardexPublicationView();
        cpOk.setId("cp-ok");
        cpOk.setTitle("Citing OK");
        cpOk.setAuthors(List.of("a2"));
        cpOk.setForum("f-ok");
        cpOk.setCoverDate("2024-01-01");
        cpOk.setVolume("42");

        ScholardexForumView rootForum = forum("f-root");
        ScholardexForumView okForum = forum("f-ok");
        okForum.setPublicationName("Forum OK");

        Score citationScore = new Score();
        citationScore.setAuthorScore(1.0);
        citationScore.setScore(2.0);
        citationScore.setCoreRankingEquivalent("Q1");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(indicatorRepository.findById("i-cit")).thenReturn(Optional.of(indicator));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(effectiveAuthorshipReadService.findConfirmedPublicationsForScoring("user@uvt.ro")).thenReturn(List.of(cited));
        when(scholardexProjectionReadService.findAllCitationsByCitedId("p1")).thenReturn(List.of(citation));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("cp-ok"))).thenReturn(List.of(cpOk));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(rootForum, okForum));
        when(scientificProductionService.calculateScientificImpactScore(any(ScoringPublicationReadModel.class), anyList(), eq(indicator)))
                .thenReturn(Map.of("Citing OK", citationScore));
        when(cacheService.getAuthorCache()).thenReturn(Map.of("a1", author, "a2", external));

        var result = facade.buildIndicatorWorkbookExport("user@uvt.ro", "i-cit");
        assertTrue(result.isPresent());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.get().workbookBytes()))) {
            var sheet = workbook.getSheet("Citations");
            var header = sheet.getRow(0);
            assertEquals("Cited Title", header.getCell(0).getStringCellValue());
            assertEquals("Citing Title", header.getCell(1).getStringCellValue());
            assertEquals("Authors", header.getCell(2).getStringCellValue());
            assertEquals("Forum", header.getCell(3).getStringCellValue());
            assertEquals("Volume", header.getCell(4).getStringCellValue());
            assertEquals("Year", header.getCell(5).getStringCellValue());
            assertEquals("Workshop", header.getCell(6).getStringCellValue());
            assertEquals("Category", header.getCell(7).getStringCellValue());
            assertEquals("Forum Score", header.getCell(8).getStringCellValue());
            assertEquals("Author Score", header.getCell(9).getStringCellValue());
            var row = sheet.getRow(2);
            assertEquals("Root", row.getCell(0).getStringCellValue());
            assertEquals("Citing OK", row.getCell(1).getStringCellValue());
            assertEquals("Author Two", row.getCell(2).getStringCellValue());
            assertEquals("Forum OK", row.getCell(3).getStringCellValue());
            assertEquals("42", row.getCell(4).getStringCellValue());
            assertEquals("2024", row.getCell(5).getStringCellValue());
            assertEquals("No", row.getCell(6).getStringCellValue());
            assertEquals("Q1", row.getCell(7).getStringCellValue());
            assertEquals(2.0, row.getCell(8).getNumericCellValue());
            assertEquals(1.0, row.getCell(9).getNumericCellValue());
        }
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

    private static void ensureLegacyTemplateFixturePresent() throws Exception {
        var resourceRoot = Thread.currentThread().getContextClassLoader().getResource("");
        if (resourceRoot == null) {
            return;
        }
        Path templatePath = Path.of(resourceRoot.toURI())
                .resolve("data")
                .resolve("templates")
                .resolve("Anexa5-Fisa_articole_brevete.xlsx");
        if (Files.exists(templatePath)) {
            return;
        }
        Files.createDirectories(templatePath.getParent());
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Sheet1");
            try (var out = Files.newOutputStream(templatePath)) {
                workbook.write(out);
            }
        }
    }

    private Object invokePrivate(String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = UserReportFacade.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try {
            return method.invoke(facade, args);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
