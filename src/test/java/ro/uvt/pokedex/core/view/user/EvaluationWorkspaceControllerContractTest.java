package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.EvaluationSnapshotRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(EvaluationWorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class EvaluationWorkspaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private UserReportFacade userReportFacade;
    @MockitoBean
    private UserIndividualReportRunService userIndividualReportRunService;
    @MockitoBean
    private UserIndicatorResultService userIndicatorResultService;
    @MockitoBean
    private UserIndividualReportRunRepository userIndividualReportRunRepository;
    @MockitoBean
    private EvaluationSnapshotRepository evaluationSnapshotRepository;
    @MockitoBean
    private ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade reportExportFacade;
    @MockitoBean
    private ro.uvt.pokedex.core.service.reporting.transfer.ReportImportVerificationFacade reportImportVerificationFacade;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade userActivityInstanceFacade;

    @Test
    void evaluationTemplateExposesSnapshotRegionAndModalDescriptions() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/user/individual-report-view.html"));

        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-compare-select\" class=\"form-control form-control-sm\" aria-label=\"Select comparison run or snapshot\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-snapshots-panel\" hidden role=\"region\" aria-labelledby=\"eval-snapshots-title\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("id=\"eval-snapshots-title\""));
        org.junit.jupiter.api.Assertions.assertTrue(template.contains("aria-describedby=\"citationModalPubTitle citationModalTotal\""));
    }

    @Test
    void showEvaluationAddsConfirmedPublicationWarningForPublicationBackedReports() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        IndividualReport report = report("rep-1", publicationIndicator("ind-pub"));

        when(userReportFacade.buildIndividualReportsListView("u@uvt.ro"))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userReportFacade.reportUsesPublicationScoring("rep-1")).thenReturn(true);
        when(userReportFacade.hasConfirmedPublicationsForScoring("u@uvt.ro")).thenReturn(false);
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(runDto("run-1")));
        when(userIndividualReportRunRepository.findByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("u@uvt.ro", "rep-1"))
                .thenReturn(List.of());

        mockMvc.perform(get("/user/evaluation").param("report", "rep-1").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individual-report-view"))
                .andExpect(model().attribute("confirmedPublicationScoringWarning", true));
    }

    @Test
    void showEvaluationOmitsConfirmedPublicationWarningForActivityOnlyReports() throws Exception {
        User user = userPrincipal("u@uvt.ro");
        Indicator activityIndicator = new Indicator();
        activityIndicator.setId("ind-act");
        activityIndicator.setOutputType(Indicator.Type.GENERIC_ACTIVITIES);
        var activity = new ro.uvt.pokedex.core.model.activities.Activity();
        activity.setName("Mentoring");
        activityIndicator.setActivity(activity);
        IndividualReport report = report("rep-1", activityIndicator);

        when(userReportFacade.buildIndividualReportsListView("u@uvt.ro"))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userReportFacade.reportUsesPublicationScoring("rep-1")).thenReturn(false);
        when(userIndividualReportRunService.getOrCreateLatestRun("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(runDto("run-1")));
        when(userIndividualReportRunRepository.findByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("u@uvt.ro", "rep-1"))
                .thenReturn(List.of());

        mockMvc.perform(get("/user/evaluation").param("report", "rep-1").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individual-report-view"))
                .andExpect(model().attribute("confirmedPublicationScoringWarning", false));
    }

    private static IndividualReport report(String id, Indicator indicator) {
        IndividualReport report = new IndividualReport();
        report.setId(id);
        report.setTitle("Report");
        report.setIndicators(List.of(indicator));
        report.setCriteria(List.of());
        return report;
    }

    private static Indicator publicationIndicator(String id) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setOutputType(Indicator.Type.PUBLICATIONS);
        return indicator;
    }

    private static IndividualReportRunDto runDto(String id) {
        return new IndividualReportRunDto(
                id,
                "rep-1",
                List.of(),
                Map.of(),
                Map.of(),
                Instant.parse("2026-04-16T12:00:00Z"),
                IndividualReportRunDto.Source.BUILT
        );
    }

    private static User userPrincipal(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private static RequestPostProcessor authenticatedUser(User user) {
        return request -> {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "RESEARCHER");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
