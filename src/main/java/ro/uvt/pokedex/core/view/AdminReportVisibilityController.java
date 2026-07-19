package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.ReportVisibilityService;

import java.util.Optional;

/**
 * Endpoints for managing report visibility:
 *
 * <ul>
 *   <li>{@code GET  /admin/divisions/{id}/reports/select} — division head picks which reports apply.</li>
 *   <li>{@code POST /admin/divisions/{id}/reports/select} — toggle one selection.</li>
 *   <li>{@code GET  /admin/departments/{id}/reports/visibility} — department head sees inherited reports and can hide.</li>
 *   <li>{@code POST /admin/departments/{id}/reports/visibility} — toggle one hide.</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class AdminReportVisibilityController {

    private final ReportVisibilityService reportVisibilityService;
    private final AdminCatalogFacade adminCatalogFacade;

    @GetMapping("/admin/divisions/{divisionId}/reports/select")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String selectForDivision(@PathVariable String divisionId, Authentication auth, Model model) {
        var division = adminCatalogFacade.findOrgDivisionById(divisionId);
        if (division.isEmpty()) return "redirect:/admin/divisions";
        // 403 if the principal isn't allowed to manage selection here.
        if (!reportVisibilityService.canManageDivisionSelection(division.get(), auth)) {
            throw new AccessDeniedException("Only the division head or platform admin can change report selection.");
        }
        ReportVisibilityService.DivisionSelectionView view =
                reportVisibilityService.buildDivisionSelectionView(divisionId);
        model.addAttribute("division", division.get());
        model.addAttribute("catalog", view.catalog());
        model.addAttribute("selectedIds", view.selectedReportIds());
        model.addAttribute("backHref", "/admin/divisions/" + divisionId + "/reports");
        model.addAttribute("postUrl", "/admin/divisions/" + divisionId + "/reports/select");
        return "admin/division-report-select";
    }

    @PostMapping("/admin/divisions/{divisionId}/reports/select")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String toggleDivisionSelection(@PathVariable String divisionId,
                                          @RequestParam("reportId") String reportId,
                                          @RequestParam(value = "selected", defaultValue = "false") boolean selected,
                                          Authentication auth,
                                          RedirectAttributes flash) {
        try {
            reportVisibilityService.setDivisionSelection(divisionId, reportId, selected, auth);
            flash.addFlashAttribute("successMessage",
                    selected ? "Report selected for this division." : "Report deselected.");
        } catch (IllegalArgumentException | AccessDeniedException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/divisions/" + divisionId + "/reports/select";
    }

    @GetMapping("/admin/departments/{departmentId}/reports/visibility")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String visibilityForDepartment(@PathVariable String departmentId, Authentication auth, Model model) {
        Optional<ro.uvt.pokedex.core.model.org.Department> deptOpt = adminCatalogFacade.findDepartmentById(departmentId);
        if (deptOpt.isEmpty()) return "redirect:/admin/departments";
        if (!reportVisibilityService.canManageDepartmentHide(deptOpt.get(), auth)) {
            throw new AccessDeniedException("Only the department head, parent division head, or platform admin can hide reports here.");
        }
        ReportVisibilityService.DepartmentVisibilityView view =
                reportVisibilityService.buildDepartmentVisibilityView(departmentId);
        model.addAttribute("department", deptOpt.get());
        model.addAttribute("inherited", view.inheritedReports());
        model.addAttribute("hiddenIds", view.hiddenReportIds());
        model.addAttribute("backHref", "/admin/departments/" + departmentId + "/reports");
        model.addAttribute("postUrl", "/admin/departments/" + departmentId + "/reports/visibility");
        return "admin/department-report-visibility";
    }

    @PostMapping("/admin/departments/{departmentId}/reports/visibility")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String toggleDepartmentHide(@PathVariable String departmentId,
                                       @RequestParam("reportId") String reportId,
                                       @RequestParam(value = "hidden", defaultValue = "false") boolean hidden,
                                       Authentication auth,
                                       RedirectAttributes flash) {
        try {
            reportVisibilityService.setDepartmentHide(departmentId, reportId, hidden, auth);
            flash.addFlashAttribute("successMessage",
                    hidden ? "Report hidden for this department." : "Report restored.");
        } catch (IllegalArgumentException | AccessDeniedException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/departments/" + departmentId + "/reports/visibility";
    }
}
