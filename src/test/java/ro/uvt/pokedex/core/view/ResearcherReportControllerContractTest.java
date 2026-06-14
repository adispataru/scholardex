package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;
import ro.uvt.pokedex.core.service.security.ResearcherAccessService;
import ro.uvt.pokedex.core.view.user.IndividualReportViewModelAssembler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ResearcherReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalControllerAdvice.class, IndividualReportViewModelAssembler.class})
class ResearcherReportControllerContractTest {

    private static final String EMAIL = "florin@e-uvt.ro";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearcherAccessService researcherAccess;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private UserReportFacade userReportFacade;
    @MockitoBean
    private UserIndividualReportRunService userIndividualReportRunService;
    @MockitoBean
    private UserIndividualReportRunRepository userIndividualReportRunRepository; // assembler dependency

    private IndividualReport report() {
        Indicator ind = new Indicator();
        ind.setId("ind-1");
        ind.setName("Info_X");
        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setTitle("FV Test");
        report.setIndicators(List.of(ind));
        AbstractReport.Criterion crit = new AbstractReport.Criterion();
        crit.setName("C1");
        crit.setIndicatorIndices(List.of(0));
        report.setCriteria(List.of(crit));
        return report;
    }

    private User researcher() {
        User u = new User();
        u.setEmail(EMAIL);
        return u;
    }

    @Test
    void pickerRendersInScopeResearchers() throws Exception {
        when(researcherAccess.findInScopeResearchers(any())).thenReturn(List.of(researcher()));
        when(userService.findDisplayLabels(any())).thenReturn(Map.of(EMAIL, "Florin S"));

        mockMvc.perform(get("/reports/researcher"))
                .andExpect(status().isOk())
                .andExpect(view().name("reports/researcher-picker"))
                .andExpect(content().string(containsString("Florin S")));
    }

    @Test
    void viewRendersDelegatedReadOnlyReport() throws Exception {
        IndividualReport report = report();
        IndividualReportRunDto run = new IndividualReportRunDto(
                "run-1", "rep-1", List.of(),
                Map.of("ind-1", 12.5), Map.of(0, 12.5),
                Instant.now(), IndividualReportRunDto.Source.PERSISTED, "admin@e-uvt.ro");

        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(researcher()));
        when(userService.findDisplayLabels(List.of(EMAIL))).thenReturn(Map.of(EMAIL, "Florin S"));
        when(userReportFacade.buildIndividualReportsListView(EMAIL))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.findLatestRun(EMAIL, "rep-1")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/reports/researcher/{email}", EMAIL).param("report", "rep-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individual-report-view"))
                .andExpect(model().attribute("delegated", true))
                .andExpect(model().attribute("delegatedSubjectEmail", EMAIL))
                // read-only banner present, interactive "Refresh All" action absent
                .andExpect(content().string(containsString("read-only")))
                .andExpect(content().string(not(containsString("Refresh All"))));

        // Delegated view must never create/persist a run.
        verify(userIndividualReportRunService).findLatestRun(EMAIL, "rep-1");
        verify(userIndividualReportRunService, never()).getOrCreateLatestRun(any(), any());
    }

    @Test
    void viewDoesNotCreateRunWhenNoneExists() throws Exception {
        IndividualReport report = report();
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(researcher()));
        when(userService.findDisplayLabels(List.of(EMAIL))).thenReturn(Map.of(EMAIL, "Florin S"));
        when(userReportFacade.buildIndividualReportsListView(EMAIL))
                .thenReturn(new UserReportsListViewModel(List.of(report)));
        when(userReportFacade.findIndividualReportById("rep-1")).thenReturn(Optional.of(report));
        when(userIndividualReportRunService.findLatestRun(EMAIL, "rep-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/reports/researcher/{email}", EMAIL).param("report", "rep-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/individual-report-view"))
                .andExpect(model().attribute("noRun", true))
                .andExpect(model().attribute("delegated", true));

        verify(userIndividualReportRunService).findLatestRun(EMAIL, "rep-1");
        verify(userIndividualReportRunService, never()).getOrCreateLatestRun(any(), any());
    }

    @Test
    void refreshDelegatesToServiceWithActorAndRedirects() throws Exception {
        var actor = new TestingAuthenticationToken("admin@e-uvt.ro", "x");

        mockMvc.perform(post("/reports/researcher/{email}/report/{reportId}/refresh", EMAIL, "rep-1")
                        .principal(actor))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reports/researcher/" + EMAIL + "?report=rep-1"));

        // Refresh runs against the researcher's data but is attributed to the acting principal.
        verify(userIndividualReportRunService).refreshRunWithAllIndicators(EMAIL, "rep-1", "admin@e-uvt.ro");
    }
}
