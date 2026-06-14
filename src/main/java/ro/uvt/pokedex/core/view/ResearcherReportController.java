package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.security.ResearcherAccessService;
import ro.uvt.pokedex.core.view.user.IndividualReportViewModelAssembler;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Delegated, read-only view of the individual evaluation report a specific researcher sees, for
 * admins and supervisors (H59 slice 1: admin-only effective access — supervisor scope arrives in
 * slice 2). Renders the same {@code user/individual-report-view} template as the researcher's own
 * page, in {@code delegated} (read-only) mode, and never mutates the researcher's data: it resolves
 * the latest <em>existing</em> run via {@link UserIndividualReportRunService#findLatestRun} rather
 * than the create-on-miss path.
 */
@Controller
@RequestMapping("/reports/researcher")
@RequiredArgsConstructor
public class ResearcherReportController {

    private final ResearcherAccessService researcherAccess;
    private final UserService userService;
    private final UserReportFacade userReportFacade;
    private final UserIndividualReportRunService userIndividualReportRunService;
    private final IndividualReportViewModelAssembler individualReportViewModelAssembler;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','SUPERVISOR')")
    public String pickResearcher(Authentication authentication, Model model) {
        List<User> researchers = researcherAccess.findInScopeResearchers(authentication);
        List<String> emails = researchers.stream().map(User::getEmail).toList();
        Map<String, String> labels = userService.findDisplayLabels(emails);
        model.addAttribute("researchers", researchers);
        model.addAttribute("researcherLabels", labels);
        return "reports/researcher-picker";
    }

    @GetMapping("/{email}")
    @PreAuthorize("@researcherAccess.canView(#email, authentication)")
    public String viewResearcherReport(@PathVariable String email,
                                       @RequestParam(name = "report", required = false) String reportId,
                                       Model model) {
        Optional<User> researcherOpt = userService.getUserByEmail(email);
        if (researcherOpt.isEmpty()) {
            return "redirect:/reports/researcher";
        }
        User researcher = researcherOpt.get();

        model.addAttribute("delegated", true);
        model.addAttribute("delegatedSubjectEmail", email);
        model.addAttribute("delegatedSubjectName",
                userService.findDisplayLabels(List.of(email)).getOrDefault(email, email));

        List<IndividualReport> reports = userReportFacade.buildIndividualReportsListView(email).individualReports();
        if (reports.isEmpty()) {
            model.addAttribute("user", researcher);
            model.addAttribute("noReports", true);
            return "user/individual-report-view";
        }

        String resolvedReportId = (reportId != null && !reportId.isBlank()) ? reportId : reports.get(0).getId();
        Optional<IndividualReport> reportOpt = userReportFacade.findIndividualReportById(resolvedReportId);
        if (reportOpt.isEmpty()) {
            return "redirect:/reports/researcher/" + email;
        }
        IndividualReport report = reportOpt.get();

        // Read-only: latest existing run only — never create/persist on a delegated view.
        Optional<IndividualReportRunDto> runOpt = userIndividualReportRunService.findLatestRun(email, resolvedReportId);
        if (runOpt.isEmpty()) {
            model.addAttribute("user", researcher);
            model.addAttribute("report", report);
            model.addAttribute("allReports", reports);
            model.addAttribute("noRun", true);
            return "user/individual-report-view";
        }

        individualReportViewModelAssembler.populate(model, researcher, report, runOpt.get(), reports);
        return "user/individual-report-view";
    }

    /**
     * Delegated refresh: recompute the researcher's report and persist a new run, recording the
     * acting principal as the run's {@code triggeredByEmail} (provenance). This mutates the
     * researcher's data space (their LATEST cache, SNAPSHOT rows, and a new run) — the deliberate
     * exception to the otherwise read-only delegated surface — so it is gated by
     * {@code @researcherAccess.canRefresh}.
     */
    @PostMapping("/{email}/report/{reportId}/refresh")
    @PreAuthorize("@researcherAccess.canRefresh(#email, authentication)")
    public String refreshResearcherReport(@PathVariable String email,
                                          @PathVariable String reportId,
                                          Authentication authentication) {
        userIndividualReportRunService.refreshRunWithAllIndicators(email, reportId, authentication.getName());
        return "redirect:/reports/researcher/" + email + "?report=" + reportId;
    }
}
