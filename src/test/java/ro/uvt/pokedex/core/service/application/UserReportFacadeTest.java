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
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportStatus;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

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
    private ScopusProjectionReadService scopusProjectionReadService;
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

    @InjectMocks
    private UserReportFacade facade;

    @BeforeEach
    void defaults() {
        lenient().when(scopusProjectionReadService.findPublicationViewById(any())).thenReturn(Optional.empty());
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
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r1");

        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setScopusId(List.of("a1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(indicatorRepository.findById("i1")).thenReturn(Optional.empty());

        var result = facade.buildIndicatorWorkbookExport("user@uvt.ro", "i1");

        assertTrue(result.isEmpty());
    }

    @Test
    void buildUserCnfisWorkbookExportReturnsNotFoundWhenResearcherMissing() throws Exception {
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r-missing");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r-missing")).thenReturn(Optional.empty());

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
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r1");

        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setScopusId(List.of("a1"));

        Publication publication = new Publication();
        publication.setId("p1");
        publication.setCoverDate("2022-01-01");
        publication.setForum("f1");

        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(woSExtractor.findPublicationWosId(publication)).thenReturn(publication);
        when(scopusProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{1, 2});

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        assertEquals("data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx", result.fileName());
    }

    @Test
    void buildUserCnfisWorkbookExportAppliesInclusiveYearBoundaries() throws Exception {
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r1");

        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setScopusId(List.of("a1"));

        Publication pStart = new Publication();
        pStart.setId("pStart");
        pStart.setCoverDate("2021-01-01");
        pStart.setForum("f1");

        Publication pIn = new Publication();
        pIn.setId("pIn");
        pIn.setCoverDate("2022-06-01");
        pIn.setForum("f1");

        Publication pEnd = new Publication();
        pEnd.setId("pEnd");
        pEnd.setCoverDate("2024-12-01");
        pEnd.setForum("f1");

        Publication pOut = new Publication();
        pOut.setId("pOut");
        pOut.setCoverDate("2025-01-01");
        pOut.setForum("f1");

        Domain allDomain = new Domain();
        allDomain.setName("ALL");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(pStart, pIn, pEnd, pOut));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(woSExtractor.findPublicationWosId(any(Publication.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scopusProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{1});

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        // only in-range publications should be enriched/saved (2021..2024 inclusive)
        verify(scopusProjectionReadService, never()).savePublicationView(any());
    }

    @Test
    void buildLegacyUserCnfisWorkbookExportReturnsNotFoundWhenNoAuthors() throws Exception {
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r1");

        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setScopusId(List.of("a1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(scopusProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());

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
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r1");

        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setScopusId(List.of("a1"));

        Indicator indicator = new Indicator();
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);

        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        Publication publication = new Publication();
        publication.setId("p1");
        publication.setTitle("Paper One");
        publication.setAuthors(List.of("a1"));
        publication.setForum("f1");
        publication.setVolume("12");
        publication.setCoverDate("2023-01-01");

        Score publicationScore = new Score();
        publicationScore.setCategory("Q1");
        publicationScore.setScore(10.0);
        publicationScore.setAuthorScore(5.0);

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(indicatorRepository.findById("i1")).thenReturn(Optional.of(indicator));
        when(scopusProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(scopusProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(forum));
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
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r1");

        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setScopusId(List.of("a1"));

        Indicator indicator = new Indicator();
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);

        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");

        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum One");

        Publication publication = new Publication();
        publication.setId("p1");
        publication.setTitle("Paper One");
        publication.setAuthors(List.of("a1"));
        publication.setForum("f1");
        publication.setVolume("12");
        publication.setCoverDate("bad-date");

        Score publicationScore = new Score();
        publicationScore.setCategory("Q1");
        publicationScore.setScore(10.0);
        publicationScore.setAuthorScore(5.0);

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(indicatorRepository.findById("i1")).thenReturn(Optional.of(indicator));
        when(scopusProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(scopusProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of(forum));
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
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r1");

        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setScopusId(List.of("a1"));

        Publication pIn = new Publication();
        pIn.setId("p-in");
        pIn.setCoverDate("2022-03-01");
        pIn.setForum("f1");
        pIn.setAuthors(List.of("a1"));

        Publication pOut = new Publication();
        pOut.setId("p-out");
        pOut.setCoverDate("2025-03-01");
        pOut.setForum("f1");
        pOut.setAuthors(List.of("a1"));

        Domain allDomain = new Domain();
        allDomain.setName("ALL");
        CNFISReport2025 report = new CNFISReport2025();

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(pIn, pOut));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(woSExtractor.findPublicationWosId(any(Publication.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cnfiSScoringService2025.getReport(any(Publication.class), eq(allDomain))).thenReturn(report);
        when(scopusProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{7});

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        ArgumentCaptor<List<Publication>> publicationCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<CNFISReport2025>> reportCaptor = ArgumentCaptor.forClass(List.class);
        verify(exportService).generateCNFISReportWorkbook(publicationCaptor.capture(), reportCaptor.capture(), anyMap(), eq(List.of("a1")), eq(false));
        assertEquals(1, publicationCaptor.getValue().size());
        assertEquals("p-in", publicationCaptor.getValue().getFirst().getId());
        assertEquals(1, reportCaptor.getValue().size());
    }

    @Test
    void buildUserCnfisWorkbookExportSkipsMalformedCoverDateWithoutCrash() throws Exception {
        User user = new User();
        user.setEmail("user@uvt.ro");
        user.setResearcherId("r1");

        Researcher researcher = new Researcher();
        researcher.setId("r1");
        researcher.setScopusId(List.of("a1"));

        Publication valid = new Publication();
        valid.setId("p-valid");
        valid.setCoverDate("2022-01-01");
        valid.setForum("f1");
        valid.setAuthors(List.of("a1"));

        Publication invalid = new Publication();
        invalid.setId("p-invalid");
        invalid.setCoverDate("20AB-99-99");
        invalid.setForum("f1");
        invalid.setAuthors(List.of("a1"));

        Domain allDomain = new Domain();
        allDomain.setName("ALL");
        CNFISReport2025 report = new CNFISReport2025();

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherService.findResearcherById("r1")).thenReturn(Optional.of(researcher));
        when(scopusProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(valid, invalid));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(woSExtractor.findPublicationWosId(any(Publication.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cnfiSScoringService2025.getReport(any(Publication.class), eq(allDomain))).thenReturn(report);
        when(scopusProjectionReadService.findForumsByIdIn(any())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{7});

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        verify(scopusProjectionReadService, never()).savePublicationView(any());

        ArgumentCaptor<List<Publication>> publicationCaptor = ArgumentCaptor.forClass(List.class);
        verify(exportService).generateCNFISReportWorkbook(publicationCaptor.capture(), anyList(), anyMap(), eq(List.of("a1")), eq(false));
        assertEquals(1, publicationCaptor.getValue().size());
        assertEquals("p-valid", publicationCaptor.getValue().getFirst().getId());
    }
}
