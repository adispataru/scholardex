package ro.uvt.pokedex.core.view;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.service.application.IndividualReportsManagementFacade;

import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/admin/individualReports")
@RequiredArgsConstructor
public class AdminIndividualReportsController {

    private final IndividualReportsManagementFacade individualReportsManagementFacade;

    @GetMapping
    public String listIndividualReports(Model model) {
        List<IndividualReport> individualReports = individualReportsManagementFacade.listIndividualReports();
        model.addAttribute("individualReports", individualReports);
        List<Indicator> all = individualReportsManagementFacade.listIndicatorsSortedByName();
        model.addAttribute("allIndicators", all);
        model.addAttribute("allAffiliations", individualReportsManagementFacade.listInstitutions());
        model.addAttribute("individualReport", new IndividualReport());
        return "admin/individualReports";
    }

    @PostMapping("/create")
    public String createIndividualReport(@ModelAttribute IndividualReport individualReport, RedirectAttributes redirectAttributes) {
        individualReportsManagementFacade.saveIndividualReport(individualReport);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report created successfully.");
        return "redirect:/admin/individualReports";
    }

    @GetMapping("/edit/{id}")
    public String editIndividualReport(@PathVariable String id, Model model) {
        IndividualReport individualReport = individualReportsManagementFacade.findIndividualReportRequired(id);
        model.addAttribute("individualReport", individualReport);
        model.addAttribute("allIndicators", individualReportsManagementFacade.listIndicators());
        model.addAttribute("reportIndicators", individualReport.getIndicators());
        model.addAttribute("allAffiliations", individualReportsManagementFacade.listInstitutions());
        model.addAttribute("allPositions", Position.values());
        return "admin/edit-individualReport";
    }

    @PostMapping("/update")
    public String updateIndividualReport(@ModelAttribute IndividualReport individualReport, RedirectAttributes redirectAttributes) {
        individualReportsManagementFacade.saveIndividualReport(individualReport);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report updated successfully.");
        return "redirect:/admin/individualReports";
    }

    @PostMapping("/delete/{id}")
    public String deleteIndividualReport(@PathVariable String id, RedirectAttributes redirectAttributes) {
        individualReportsManagementFacade.deleteIndividualReport(id);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report deleted successfully.");
        return "redirect:/admin/individualReports";
    }

    @PostMapping("/duplicate/{id}")
    public String duplicateIndividualReport(@PathVariable String id, RedirectAttributes redirectAttributes) {
        Optional<IndividualReport> byId = individualReportsManagementFacade.duplicateIndividualReport(id);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report duplicated successfully.");
        return "redirect:/admin/individualReports";
    }
}
