package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import ro.uvt.pokedex.core.service.application.DivisionReportFacade;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;

import java.util.Optional;

/**
 * Surfaces individual-report roll-ups for a single OrgDivision (Faculty / Institute / Service).
 * Aggregates researchers from every department under the division; per-researcher scoring
 * is shared with the Department view.
 */
@Controller
@RequestMapping("/admin/divisions/{divisionId}/reports")
@RequiredArgsConstructor
public class AdminDivisionReportsController {

    private final DivisionReportFacade divisionReportFacade;

    @GetMapping
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String listReports(@PathVariable String divisionId, Model model) {
        var division = divisionReportFacade.findDivision(divisionId);
        if (division.isEmpty()) return "redirect:/admin/divisions";
        model.addAttribute("unitType", "division");
        model.addAttribute("unitName", division.get().getName());
        model.addAttribute("unitId", divisionId);
        model.addAttribute("backHref", "/admin/divisions");
        model.addAttribute("reports", divisionReportFacade.listReportsVisibleForDivision(divisionId));
        model.addAttribute("reportLinkPrefix", "/admin/divisions/" + divisionId + "/reports/");
        return "admin/orgunit-reports-list";
    }

    @GetMapping("/{reportId}")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String viewReport(@PathVariable String divisionId,
                             @PathVariable String reportId,
                             Model model) {
        Optional<OrgUnitReportViewModel> view = divisionReportFacade.buildView(divisionId, reportId);
        if (view.isEmpty()) return "redirect:/admin/divisions/" + divisionId + "/reports";
        OrgUnitReportViewModel vm = view.get();
        model.addAttribute("unitType", "division");
        model.addAttribute("vm", vm);
        model.addAttribute("backHref", "/admin/divisions/" + divisionId + "/reports");
        return "admin/orgunit-report-view";
    }
}
