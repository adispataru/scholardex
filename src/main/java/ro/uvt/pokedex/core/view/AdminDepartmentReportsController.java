package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.service.application.DepartmentReportFacade;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;

import java.time.Instant;
import java.util.Optional;

/**
 * Surfaces individual-report roll-ups for a single Department. Researchers are resolved via
 * current DepartmentAffiliation entries; scores come from each member's latest persisted run.
 */
@Controller
@RequestMapping("/admin/departments/{departmentId}/reports")
@RequiredArgsConstructor
public class AdminDepartmentReportsController {

    private final DepartmentReportFacade departmentReportFacade;

    @GetMapping
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String listReports(@PathVariable String departmentId, Model model) {
        var department = departmentReportFacade.findDepartment(departmentId);
        if (department.isEmpty()) return "redirect:/admin/departments";
        model.addAttribute("unitType", "department");
        model.addAttribute("unitName", department.get().getName());
        model.addAttribute("unitId", departmentId);
        model.addAttribute("backHref", "/admin/departments");
        model.addAttribute("reports", departmentReportFacade.listReportsVisibleForDepartment(departmentId));
        model.addAttribute("reportLinkPrefix", "/admin/departments/" + departmentId + "/reports/");
        return "admin/orgunit-reports-list";
    }

    @GetMapping("/{reportId}")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String viewReport(@PathVariable String departmentId,
                             @PathVariable String reportId,
                             @RequestParam(value = "compareTo", required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant compareTo,
                             Model model) {
        Optional<OrgUnitReportViewModel> view = departmentReportFacade.buildView(departmentId, reportId, compareTo);
        if (view.isEmpty()) return "redirect:/admin/departments/" + departmentId + "/reports";
        OrgUnitReportViewModel vm = view.get();
        model.addAttribute("unitType", "department");
        model.addAttribute("vm", vm);
        model.addAttribute("backHref", "/admin/departments/" + departmentId + "/reports");
        return "admin/orgunit-report-view";
    }
}
