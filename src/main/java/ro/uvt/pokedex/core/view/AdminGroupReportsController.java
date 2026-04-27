package ro.uvt.pokedex.core.view;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.reporting.GroupReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.service.application.GroupReportsManagementFacade;
import ro.uvt.pokedex.core.service.application.model.BreadcrumbItem;

import java.util.List;

@Controller
@RequestMapping("/admin/groupReports")
@RequiredArgsConstructor
public class AdminGroupReportsController {

    private final GroupReportsManagementFacade groupReportsManagementFacade;

    @GetMapping
    public String listGroupReports(Model model) {
        List<GroupReport> groupReports = groupReportsManagementFacade.listGroupReports();
        model.addAttribute("groupReports", groupReports);
        model.addAttribute("indicators", groupReportsManagementFacade.listIndicators());
        model.addAttribute("groups", groupReportsManagementFacade.listGroups());
        model.addAttribute("groupReport", new GroupReport());
        return "admin/groupReports";
    }

    @PostMapping("/create")
    public String createGroupReport(@ModelAttribute GroupReport groupReport, RedirectAttributes redirectAttributes) {
        groupReportsManagementFacade.saveGroupReport(groupReport);
        redirectAttributes.addFlashAttribute("successMessage", "Group Report created successfully.");
        return "redirect:/admin/groupReports";
    }

    @GetMapping("/edit/{id}")
    public String editGroupReport(@PathVariable String id, Model model) {
        GroupReport groupReport = groupReportsManagementFacade.findGroupReport(id).orElse(null);
        populateGroupReportEditModel(model, groupReport);
        return "admin/edit-groupReport";
    }

    @GetMapping("/apply/{id}")
    public String applyGroupReport(@PathVariable String id, Model model) {
        GroupReport groupReport = groupReportsManagementFacade.findGroupReport(id).orElse(null);
        populateGroupReportEditModel(model, groupReport);
        return "admin/edit-groupReport";
    }

    private void populateGroupReportEditModel(Model model, GroupReport groupReport) {
        model.addAttribute("groupReport", groupReport);
        model.addAttribute("adminFormObject", groupReport);
        model.addAttribute("allIndicators", groupReportsManagementFacade.listIndicators());
        model.addAttribute("reportIndicators", groupReport != null ? groupReport.getIndicators() : List.of());
        model.addAttribute("allPositions", Position.values());
        String label = groupReport == null || groupReport.getTitle() == null
                ? "Edit Group Report"
                : groupReport.getTitle();
        model.addAttribute("breadcrumbs", List.of(
                new BreadcrumbItem("Group Reports", "/admin/groupReports"),
                new BreadcrumbItem(label)
        ));
    }

    @PostMapping("/update")
    public String updateGroupReport(@ModelAttribute GroupReport groupReport, RedirectAttributes redirectAttributes) {
        groupReportsManagementFacade.saveGroupReport(groupReport);
        redirectAttributes.addFlashAttribute("successMessage", "Group Report updated successfully.");
        return "redirect:/admin/groupReports";
    }

    @PostMapping("/delete/{id}")
    public String deleteGroupReport(@PathVariable String id, RedirectAttributes redirectAttributes) {
        groupReportsManagementFacade.deleteGroupReport(id);
        redirectAttributes.addFlashAttribute("successMessage", "Group Report deleted successfully.");
        return "redirect:/admin/groupReports";
    }
}
