package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.ReportComparisonFacade;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.ReportComparisonViewModel;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.ReportTransferFacade;
import ro.uvt.pokedex.core.service.security.ResearcherAccessService;
import ro.uvt.pokedex.core.view.IndicatorDetailResponseAssembler.CitationDetailResponse;
import ro.uvt.pokedex.core.view.IndicatorDetailResponseAssembler.IndicatorDetailResponse;
import ro.uvt.pokedex.core.view.user.IndividualReportViewModelAssembler;

import java.util.List;
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
    private final ro.uvt.pokedex.core.service.application.UserIndicatorResultService userIndicatorResultService;
    private final UserIndividualReportRunService userIndividualReportRunService;
    private final IndividualReportViewModelAssembler individualReportViewModelAssembler;
    private final ReportTransferFacade reportTransferFacade;
    private final ReportComparisonFacade reportComparisonFacade;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','SUPERVISOR')")
    public String pickResearcher(Authentication authentication, Model model) {
        model.addAttribute("researchers", researcherAccess.findInScopeResearchers(authentication));
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
        model.addAttribute("compatibleReport",
                reportComparisonFacade.findCompatibleReport(email, report).orElse(null));
        return "user/individual-report-view";
    }

    /**
     * Criterion-level comparison of this report's latest run against its compatible counterpart's
     * latest run (today: FV Info 2016 ↔ FV Info 2026 only — {@link ReportComparisonFacade}).
     * Read-only, delegated-only (supervisor/admin); redirects back to the single-report view when no
     * compatible report is configured or assigned to this researcher.
     */
    @GetMapping("/{email}/report/{reportId}/compare")
    @PreAuthorize("@researcherAccess.canView(#email, authentication)")
    public String compareReports(@PathVariable String email, @PathVariable String reportId, Model model) {
        Optional<User> researcherOpt = userService.getUserByEmail(email);
        if (researcherOpt.isEmpty()) {
            return "redirect:/reports/researcher";
        }
        User researcher = researcherOpt.get();

        Optional<IndividualReport> reportOpt = userReportFacade.findIndividualReportById(reportId);
        if (reportOpt.isEmpty()) {
            return "redirect:/reports/researcher/" + email;
        }
        IndividualReport report = reportOpt.get();

        Optional<IndividualReport> compatibleOpt = reportComparisonFacade.findCompatibleReport(email, report);
        if (compatibleOpt.isEmpty()) {
            return "redirect:/reports/researcher/" + email + "?report=" + reportId;
        }

        ReportComparisonViewModel comparison =
                reportComparisonFacade.buildComparison(researcher, report, compatibleOpt.get());

        model.addAttribute("delegatedSubjectEmail", email);
        model.addAttribute("delegatedSubjectName",
                userService.findDisplayLabels(List.of(email)).getOrDefault(email, email));
        model.addAttribute("comparison", comparison);
        return "reports/report-compare";
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

    /**
     * Delegated export: download the researcher's report exactly as they would. Strictly read-only —
     * {@code forceRefresh} is always false, so the export never mutates the researcher's cached
     * results (an admin wanting fresh numbers refreshes first). The facade validates that any
     * supplied {@code run} belongs to this researcher.
     */
    @GetMapping("/{email}/report/{reportId}/export")
    @ResponseBody
    @PreAuthorize("@researcherAccess.canView(#email, authentication)")
    public ResponseEntity<?> exportResearcherReport(@PathVariable String email,
                                                    @PathVariable String reportId,
                                                    @RequestParam(name = "run", required = false) String runId,
                                                    @RequestParam(name = "format", defaultValue = "XLSX") ReportFormat format) {
        ReportTransferFacade.ExportOutcome outcome =
                reportTransferFacade.exportRunOutcome(email, reportId, runId, format, false);
        if (!outcome.isSuccess()) {
            return ResponseEntity.status(ReportExportHttpStatus.of(outcome.failureReason())).body(outcome.message());
        }
        ReportTransferFacade.ExportedReport exported = outcome.exportedReport();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(exported.contentType()))
                .header("Content-Disposition", "attachment; filename=\"" + exported.filename() + "\"")
                .body(new ByteArrayResource(exported.bytes()));
    }

    /**
     * Delegated indicator drilldown (read-only). Always report-scoped — served from the last run
     * refresh's fingerprint-fresh indicator snapshot when available, else a live compute; NEITHER
     * path persists anything ({@code getReportScopedDetail} never writes), and it never falls back
     * to the cache-writing {@code getOrCreateLatest} path the researcher's own endpoint uses. 404s
     * when the indicator yields nothing. The JSON is built by the shared
     * {@link IndicatorDetailResponseAssembler}, so it is identical to the researcher's own drilldown.
     */
    @GetMapping("/{email}/indicator/{indicatorId}/detail")
    @ResponseBody
    @PreAuthorize("@researcherAccess.canView(#email, authentication)")
    public ResponseEntity<IndicatorDetailResponse> researcherIndicatorDetail(@PathVariable String email,
                                                                             @PathVariable String indicatorId,
                                                                             @RequestParam("report") String reportId) {
        Optional<IndicatorApplyResultDto> result =
                userIndicatorResultService.getReportScopedDetail(email, reportId, indicatorId);
        return result.map(r -> ResponseEntity.ok(
                        IndicatorDetailResponseAssembler.buildDetail(r, userReportFacade::resolveForumName)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{email}/indicator/{indicatorId}/citations")
    @ResponseBody
    @PreAuthorize("@researcherAccess.canView(#email, authentication)")
    public ResponseEntity<CitationDetailResponse> researcherCitationDetail(@PathVariable String email,
                                                                           @PathVariable String indicatorId,
                                                                           @RequestParam("pub") String pubTitle,
                                                                           @RequestParam("report") String reportId) {
        Optional<IndicatorApplyResultDto> result =
                userIndicatorResultService.getReportScopedDetail(email, reportId, indicatorId);
        return result.map(r -> ResponseEntity.ok(IndicatorDetailResponseAssembler.buildCitations(r, pubTitle)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
