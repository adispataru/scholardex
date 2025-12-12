package ro.uvt.pokedex.core.view;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.reporting.GroupReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupReportRepository;

import java.util.List;

@Controller
@RequestMapping("/admin/groupReports")
public class AdminGroupReportsController {

    @Autowired
    private GroupReportRepository groupReportRepository;

    @Autowired
    private IndicatorRepository indicatorRepository;

    @Autowired
    private GroupRepository groupRepository;

    @GetMapping
    public String listGroupReports(Model model) {
        List<GroupReport> groupReports = groupReportRepository.findAll();
        model.addAttribute("groupReports", groupReports);
        model.addAttribute("indicators", indicatorRepository.findAll());
        model.addAttribute("groups", groupRepository.findAll());
        model.addAttribute("groupReport", new GroupReport());
        return "admin/groupReports";
    }

    @PostMapping("/create")
    public String createGroupReport(@ModelAttribute GroupReport groupReport, RedirectAttributes redirectAttributes) {
        groupReportRepository.save(groupReport);
        redirectAttributes.addFlashAttribute("successMessage", "Group Report created successfully.");
        return "redirect:/admin/groupReports";
    }

    @GetMapping("/edit/{id}")
    public String editGroupReport(@PathVariable String id, Model model) {
        GroupReport groupReport = groupReportRepository.findById(id).orElse(null);
        model.addAttribute("groupReport", groupReport);
        model.addAttribute("allIndicators", indicatorRepository.findAll());
        model.addAttribute("reportIndicators", groupReport.getIndicators());
        return "admin/edit-groupReport";
    }

    @GetMapping("/apply/{id}")
    public String applyGroupReport(@PathVariable String id, Model model) {
        GroupReport groupReport = groupReportRepository.findById(id).orElse(null);
        model.addAttribute("groupReport", groupReport);
        model.addAttribute("allIndicators", indicatorRepository.findAll());
        model.addAttribute("reportIndicators", groupReport.getIndicators());
        return "admin/edit-groupReport";
    }

    @PostMapping("/update")
    public String updateGroupReport(@ModelAttribute GroupReport groupReport, RedirectAttributes redirectAttributes) {
        groupReportRepository.save(groupReport);
        redirectAttributes.addFlashAttribute("successMessage", "Group Report updated successfully.");
        return "redirect:/admin/groupReports";
    }

    @GetMapping("/delete/{id}")
    public String deleteGroupReport(@PathVariable String id, RedirectAttributes redirectAttributes) {
        groupReportRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("successMessage", "Group Report deleted successfully.");
        return "redirect:/admin/groupReports";
    }
}
