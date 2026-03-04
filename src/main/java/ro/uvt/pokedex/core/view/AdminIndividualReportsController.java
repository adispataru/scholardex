package ro.uvt.pokedex.core.view;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/admin/individualReports")
public class AdminIndividualReportsController {

    @Autowired
    private IndividualReportRepository individualReportRepository;

    @Autowired
    private IndicatorRepository indicatorRepository;

    @Autowired
    private InstitutionRepository institutionRepository;
    private String Country = "Romania";

    @GetMapping
    public String listIndividualReports(Model model) {
        List<IndividualReport> individualReports = individualReportRepository.findAll();
        model.addAttribute("individualReports", individualReports);
        List<Indicator> all = indicatorRepository.findAll();
        all.sort(Comparator.comparing(Indicator::getName));
        model.addAttribute("allIndicators", all);
        model.addAttribute("allAffiliations", institutionRepository.findAll());
        model.addAttribute("individualReport", new IndividualReport());
        return "admin/individualReports";
    }

    @PostMapping("/create")
    public String createIndividualReport(@ModelAttribute IndividualReport individualReport, RedirectAttributes redirectAttributes) {
        individualReportRepository.save(individualReport);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report created successfully.");
        return "redirect:/admin/individualReports";
    }

    @GetMapping("/edit/{id}")
    public String editIndividualReport(@PathVariable String id, Model model) {
        IndividualReport individualReport = individualReportRepository.findById(id).get();
        model.addAttribute("individualReport", individualReport);
        model.addAttribute("allIndicators", indicatorRepository.findAll());
        model.addAttribute("reportIndicators", individualReport.getIndicators());
        model.addAttribute("allAffiliations", institutionRepository.findAll());
        model.addAttribute("allPositions", Position.values());
        return "admin/edit-individualReport";
    }

    @PostMapping("/update")
    public String updateIndividualReport(@ModelAttribute IndividualReport individualReport, RedirectAttributes redirectAttributes) {
        individualReportRepository.save(individualReport);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report updated successfully.");
        return "redirect:/admin/individualReports";
    }

    @PostMapping("/delete/{id}")
    public String deleteIndividualReport(@PathVariable String id, RedirectAttributes redirectAttributes) {
        individualReportRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report deleted successfully.");
        return "redirect:/admin/individualReports";
    }

    @PostMapping("/duplicate/{id}")
    public String duplicateIndividualReport(@PathVariable String id, RedirectAttributes redirectAttributes) {
        Optional<IndividualReport> byId = individualReportRepository.findById(id);
        if (byId.isPresent()){
            IndividualReport individualReport = byId.get();
            individualReport.setId(null);
            individualReport.setTitle(individualReport.getTitle()+" (Copy)");
            individualReportRepository.save(individualReport);
        }
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report duplicated successfully.");
        return "redirect:/admin/individualReports";
    }
}
