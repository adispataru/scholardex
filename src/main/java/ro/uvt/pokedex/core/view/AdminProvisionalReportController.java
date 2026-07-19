package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.IndividualReportsManagementFacade;
import ro.uvt.pokedex.core.service.application.ProvisionalDepartmentReportService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * H77 S4 — admin page to run a provisional department report. The admin picks a department + a report definition;
 * each roster person is resolved to a canonical author (by name / declared source ids) and scored from that DECLARED
 * identity. The results table is clearly labelled provisional (authorship not validated by the researcher), and
 * AMBIGUOUS/UNRESOLVED people are flagged rather than dropped.
 *
 * <p>Admin-gated by the {@code /admin/**} → {@code PLATFORM_ADMIN} rule in {@code WebSecurityConfig}. Read-only:
 * running a report never writes a researcher's confirm/reject decisions.
 */
@Controller
@RequestMapping("/admin/provisional-report")
@RequiredArgsConstructor
public class AdminProvisionalReportController {

    private final AdminCatalogFacade adminCatalogFacade;
    private final IndividualReportsManagementFacade individualReportsManagementFacade;
    private final ProvisionalDepartmentReportService provisionalDepartmentReportService;

    @GetMapping
    public String form(Model model) {
        populateOptions(model);
        return "admin/provisional-report";
    }

    @PostMapping("/run")
    public String run(@RequestParam String departmentId,
                      @RequestParam String reportDefinitionId,
                      Authentication authentication,
                      Model model) {
        String adminEmail = authentication == null ? null : authentication.getName();
        ProvisionalDepartmentReportService.DepartmentProvisionalReport result =
                provisionalDepartmentReportService.run(departmentId, reportDefinitionId, adminEmail);

        populateOptions(model);
        model.addAttribute("result", result);
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("selectedReportId", reportDefinitionId);
        adminCatalogFacade.findDepartmentById(departmentId)
                .ifPresent(d -> model.addAttribute("selectedDepartmentName", d.getName()));
        individualReportsManagementFacade.findIndividualReport(reportDefinitionId)
                .ifPresent(r -> model.addAttribute("selectedReportTitle", r.getTitle()));
        return "admin/provisional-report";
    }

    private void populateOptions(Model model) {
        List<Department> departments = new ArrayList<>(adminCatalogFacade.listDepartments());
        departments.sort(Comparator.comparing(d -> d.getName() == null ? "" : d.getName(), String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("departments", departments);

        List<IndividualReport> reports = new ArrayList<>(individualReportsManagementFacade.listIndividualReports());
        reports.sort(Comparator.comparing(r -> r.getTitle() == null ? "" : r.getTitle(), String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("reports", reports);
    }
}
