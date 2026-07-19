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
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.service.application.ReportTransferFacade;

import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/admin/individualReports")
@RequiredArgsConstructor
public class AdminIndividualReportsController {

    private final IndividualReportsManagementFacade individualReportsManagementFacade;
    private final ReportTransferFacade reportTransferFacade;

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
        model.addAttribute("adminFormObject", individualReport);
        model.addAttribute("allIndicators", individualReportsManagementFacade.listIndicators());
        // Dedup indicators by id for the form so the map-binding sections don't render two dropdowns
        // for the same key (Spring joins the two values with a comma, producing junk values like
        // 'citations-per-publication,' that no option matches on reload).
        java.util.LinkedHashMap<String, ro.uvt.pokedex.core.model.reporting.Indicator> uniqueIndicators = new java.util.LinkedHashMap<>();
        if (individualReport.getIndicators() != null) {
            for (var ind : individualReport.getIndicators()) {
                if (ind != null && ind.getId() != null) uniqueIndicators.putIfAbsent(ind.getId(), ind);
            }
        }
        model.addAttribute("reportIndicators", new java.util.ArrayList<>(uniqueIndicators.values()));
        model.addAttribute("allAffiliations", individualReportsManagementFacade.listInstitutions());
        model.addAttribute("allPositions", Position.values());
        model.addAttribute("registeredReportTypeKeys", reportTransferFacade.registeredReportTypeKeys());
        List<String> declaredRoles = reportTransferFacade.declaredRoles(individualReport.getReportTypeKey());
        model.addAttribute("declaredRoles", declaredRoles);
        java.util.Map<String, List<String>> declaredBlocksByRole =
                reportTransferFacade.declaredBlocksByRole(individualReport.getReportTypeKey());
        // Flatten to a single list — for informatica-2016 there's only one STACKED_BLOCKS role, so
        // the UI can render one section regardless of which roles exist.
        List<String> allBlockNames = declaredBlocksByRole.values().stream()
                .flatMap(java.util.Collection::stream).toList();
        model.addAttribute("declaredBlocks", allBlockNames);
        // Pass the sentinel as a model attribute: a "__not_exported__" string *literal* inside a
        // Thymeleaf expression gets mangled by Thymeleaf's __...__ preprocessing (it evaluates the
        // inner `not_exported` and collapses the literal to ""), so th:selected never matched the
        // saved value and the dropdown showed "— none —" on reload. A model variable has no __ to
        // preprocess.
        model.addAttribute("excludedFromTemplateValue", reportTransferFacade.excludedFromTemplateValue());
        model.addAttribute("exportReadinessProblems",
                reportTransferFacade.validateExportReadiness(individualReport, ReportFormat.XLSX));
        return "admin/edit-individualReport";
    }

    @PostMapping("/update")
    public String updateIndividualReport(@ModelAttribute IndividualReport individualReport, RedirectAttributes redirectAttributes) {
        normalizeIndicatorsAndMaps(individualReport);
        individualReportsManagementFacade.saveIndividualReport(individualReport);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report updated successfully.");
        return "redirect:/admin/individualReports";
    }

    /**
     * Dedup the indicators list and sanitize the map values posted by the form. If an indicator
     * appears twice in the report, the form posts the same map key twice and Spring's binder
     * concatenates them into a comma-joined string (e.g. {@code 'citations-per-publication,'} or
     * just {@code ','}). Split on the first comma and keep the first non-blank token.
     */
    private void normalizeIndicatorsAndMaps(IndividualReport report) {
        if (report.getIndicators() != null) {
            java.util.LinkedHashMap<String, ro.uvt.pokedex.core.model.reporting.Indicator> uniq = new java.util.LinkedHashMap<>();
            for (var ind : report.getIndicators()) {
                if (ind != null && ind.getId() != null) uniq.putIfAbsent(ind.getId(), ind);
            }
            report.setIndicators(new java.util.ArrayList<>(uniq.values()));
        }
        sanitize(report.getIndicatorRolesByIndicatorId());
        sanitize(report.getBlockByIndicatorId());
    }

    private void sanitize(java.util.Map<String, String> map) {
        if (map == null) return;
        for (var entry : map.entrySet()) {
            String v = entry.getValue();
            if (v == null) continue;
            String firstNonBlank = "";
            for (String token : v.split(",")) {
                String t = token.trim();
                if (!t.isEmpty()) { firstNonBlank = t; break; }
            }
            entry.setValue(firstNonBlank);
        }
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
