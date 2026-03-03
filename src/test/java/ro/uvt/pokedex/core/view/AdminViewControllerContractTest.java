package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAffiliationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.AdminInstitutionReportFacade;
import ro.uvt.pokedex.core.service.application.AdminScopusFacade;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsExportViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsViewModel;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminViewControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;
    @MockBean
    private ResearcherService researcherService;
    @MockBean
    private ScopusAuthorRepository scopusAuthorRepository;
    @MockBean
    private ScopusAffiliationRepository scopusAffiliationRepository;
    @MockBean
    private ScopusPublicationRepository scopusPublicationRepository;
    @MockBean
    private ScopusForumRepository scopusForumRepository;
    @MockBean
    private ScopusCitationRepository scopusCitationRepository;
    @MockBean
    private ArtisticEventRepository artisticEventRepository;
    @MockBean
    private RankingRepository rankingRepository;
    @MockBean
    private CoreConferenceRankingRepository coreConferenceRankingRepository;
    @MockBean
    private CacheService cacheService;
    @MockBean
    private IndicatorRepository indicatorRepository;
    @MockBean
    private DomainRepository domainRepository;
    @MockBean
    private InstitutionRepository institutionRepository;
    @MockBean
    private ActivityRepository activityRepository;
    @MockBean
    private IndividualReportRepository individualReportRepository;
    @MockBean
    private AdminScopusFacade adminScopusFacade;
    @MockBean
    private AdminInstitutionReportFacade adminInstitutionReportFacade;
    @MockBean
    private RankingMaintenanceFacade rankingMaintenanceFacade;

    @Test
    void computePositionsRedirectsAndDelegates() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/computePositionsForKnownQuarters"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/rankings/wos"));

        verify(rankingMaintenanceFacade).computePositionsForKnownQuarters();
    }

    @Test
    void computeMissingQuartersRedirectsAndDelegates() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/computeQuartersAndRankingsWhereMissing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/rankings/wos"));

        verify(rankingMaintenanceFacade).computeQuartersAndRankingsWhereMissing();
    }

    @Test
    void mergeDuplicateRankingsRedirectsAndDelegates() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/mergeDuplicateRankings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/rankings/wos"));

        verify(rankingMaintenanceFacade).mergeDuplicateRankings();
    }

    @Test
    void institutionPublicationsViewRendersExpectedTemplateAndModel() throws Exception {
        Institution institution = new Institution();
        Publication publication = publication("p1", "f1", "2023-01-01");
        Author author = new Author();
        author.setId("a1");
        author.setName("Author A");
        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum A");
        IndividualReport report = new IndividualReport();
        report.setTitle("R1");

        AdminInstitutionPublicationsViewModel vm = new AdminInstitutionPublicationsViewModel(
                institution,
                List.of(publication),
                Map.of("a1", author),
                Map.of("f1", forum),
                new TreeMap<>(Map.of(2023, List.of(publication))),
                new TreeMap<>(Map.of(2023, 1L)),
                List.of(report)
        );

        when(adminInstitutionReportFacade.buildInstitutionPublicationsView(eq("i1")))
                .thenReturn(Optional.of(vm));

        mockMvc.perform(get("/admin/institutions/{id}/publications", "i1")
                        .with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/institution-publications"))
                .andExpect(model().attributeExists(
                        "authorMap",
                        "publicationsByYear",
                        "publicationsCountByYear",
                        "individualReports",
                        "forumMap",
                        "publications",
                        "institution"
                ));
    }

    @Test
    void institutionPublicationsViewRedirectsWhenInstitutionMissing() throws Exception {
        when(adminInstitutionReportFacade.buildInstitutionPublicationsView(eq("missing")))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/institutions/{id}/publications", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/institutions"));
    }

    @Test
    void institutionExportExcelReturnsWorkbookHeaders() throws Exception {
        Publication publication = publication("p1", "f1", "2023-01-01");
        publication.setEid("2-s2.0-123");
        publication.setDoi("10.1000/x");
        publication.setTitle("Paper");
        publication.setCitedbyCount(5);
        Author author = new Author();
        author.setId("a1");
        author.setName("Author A");
        Forum forum = new Forum();
        forum.setId("f1");
        forum.setPublicationName("Forum A");

        Publication citing = publication("p2", "f1", "2024-01-01");
        citing.setEid("2-s2.0-999");
        citing.setDoi("10.1000/y");
        citing.setTitle("Citing Paper");
        citing.setCitedbyCount(2);

        AdminInstitutionPublicationsExportViewModel vm = new AdminInstitutionPublicationsExportViewModel(
                new Institution(),
                List.of(publication),
                Map.of("p1", List.of(citing)),
                Map.of("a1", author),
                Map.of("f1", forum)
        );

        when(adminInstitutionReportFacade.buildInstitutionPublicationsExport(eq("i1")))
                .thenReturn(Optional.of(vm));

        byte[] content = mockMvc.perform(get("/admin/institutions/{id}/publications/exportExcel", "i1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"institution_publications.xlsx\""))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var publications = workbook.getSheet("Publications");
            var citations = workbook.getSheet("Citations");
            org.junit.jupiter.api.Assertions.assertEquals("eID", publications.getRow(0).getCell(0).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Title", publications.getRow(0).getCell(2).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("2-s2.0-123", publications.getRow(1).getCell(0).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Paper", publications.getRow(1).getCell(2).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Author A", publications.getRow(1).getCell(4).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Forum A", publications.getRow(1).getCell(5).getStringCellValue());

            org.junit.jupiter.api.Assertions.assertEquals("Cited Publication eID", citations.getRow(0).getCell(0).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Citing Publication Title", citations.getRow(0).getCell(5).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("2-s2.0-123", citations.getRow(1).getCell(0).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Citing Paper", citations.getRow(1).getCell(5).getStringCellValue());
        }
    }

    @Test
    void indicatorsPageRendersExpectedTemplateAndFrontendModelContract() throws Exception {
        Activity activity = new Activity();
        activity.setId("act-1");
        activity.setName("Activity One");
        Activity.Field field = new Activity.Field();
        field.setName("duration");
        field.setNumber(true);
        activity.setFields(List.of(field));
        activity.setReferenceFields(List.of(Activity.ReferenceField.EVENT_NAME));

        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setName("Indicator One");

        Domain domain = new Domain();
        domain.setName("CS");

        when(indicatorRepository.findAll()).thenReturn(List.of(indicator));
        when(activityRepository.findAll()).thenReturn(List.of(activity));
        when(domainRepository.findAll()).thenReturn(List.of(domain));

        mockMvc.perform(get("/admin/indicators")
                        .with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/indicators"))
                .andExpect(model().attributeExists(
                        "indicators",
                        "activities",
                        "activityDescriptions",
                        "scoringStrategies",
                        "types",
                        "indicator",
                        "domains",
                        "selectors"
                ));
    }

    @Test
    void editIndicatorPageRendersExpectedTemplateAndModelContract() throws Exception {
        Activity activity = new Activity();
        activity.setId("act-1");
        activity.setName("Activity One");
        Activity.Field field = new Activity.Field();
        field.setName("duration");
        field.setNumber(true);
        activity.setFields(List.of(field));
        activity.setReferenceFields(List.of(Activity.ReferenceField.EVENT_NAME));

        Indicator indicator = new Indicator();
        indicator.setId("ind-1");
        indicator.setName("Indicator One");

        Domain domain = new Domain();
        domain.setName("CS");

        when(indicatorRepository.findById(eq("ind-1"))).thenReturn(Optional.of(indicator));
        when(activityRepository.findAll()).thenReturn(List.of(activity));
        when(domainRepository.findAll()).thenReturn(List.of(domain));

        mockMvc.perform(get("/admin/indicators/edit/{id}", "ind-1")
                        .with(authenticatedUser("admin@uvt.ro")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/indicators-edit"))
                .andExpect(model().attributeExists(
                        "indicator",
                        "activities",
                        "activityDescriptions",
                        "scoringStrategies",
                        "types",
                        "domains",
                        "selectors"
                ));
    }

    private static Publication publication(String id, String forumId, String coverDate) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setForum(forumId);
        publication.setCoverDate(coverDate);
        publication.setAuthors(List.of("a1"));
        return publication;
    }

    private static User adminUser(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private RequestPostProcessor authenticatedUser(String email) {
        return request -> {
            User user = adminUser(email);
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "PLATFORM_ADMIN");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
