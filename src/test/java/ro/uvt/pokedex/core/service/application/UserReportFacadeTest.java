package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportStatus;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
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
    private ScopusAuthorRepository scopusAuthorRepository;
    @Mock
    private ScopusCitationRepository scopusCitationRepository;
    @Mock
    private ScopusPublicationRepository scopusPublicationRepository;
    @Mock
    private ScopusForumRepository scopusForumRepository;
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
        when(scopusPublicationRepository.findAllByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(woSExtractor.findPublicationWosId(publication)).thenReturn(publication);
        when(scopusForumRepository.findByIdIn(any())).thenReturn(List.of());
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
        when(scopusPublicationRepository.findAllByAuthorsIn(List.of("a1"))).thenReturn(List.of(pStart, pIn, pEnd, pOut));
        when(domainRepository.findByName("ALL")).thenReturn(Optional.of(allDomain));
        when(woSExtractor.findPublicationWosId(any(Publication.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scopusForumRepository.findByIdIn(any())).thenReturn(List.of());
        when(exportService.generateCNFISReportWorkbook(anyList(), anyList(), anyMap(), eq(List.of("a1")), eq(false)))
                .thenReturn(new byte[]{1});

        var result = facade.buildUserCnfisWorkbookExport("user@uvt.ro", 2021, 2024);

        assertEquals(UserWorkbookExportStatus.OK, result.status());
        // only in-range publications should be enriched/saved (2021..2024 inclusive)
        org.mockito.Mockito.verify(scopusPublicationRepository, org.mockito.Mockito.times(3)).save(any(Publication.class));
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
        when(scopusAuthorRepository.findByIdIn(List.of("a1"))).thenReturn(List.of());

        var result = facade.buildLegacyUserCnfisWorkbookExport("user@uvt.ro");

        assertEquals(UserWorkbookExportStatus.NOT_FOUND, result.status());
    }

    @Test
    void buildLegacyUserCnfisWorkbookExportReturnsUnauthorizedWhenUserMissing() throws Exception {
        when(userService.getUserByEmail("missing@uvt.ro")).thenReturn(Optional.empty());

        var result = facade.buildLegacyUserCnfisWorkbookExport("missing@uvt.ro");

        assertEquals(UserWorkbookExportStatus.UNAUTHORIZED, result.status());
    }
}
