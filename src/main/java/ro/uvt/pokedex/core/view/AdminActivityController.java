package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.service.application.ActivityManagementFacade;
import ro.uvt.pokedex.core.service.application.model.BreadcrumbItem;

import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/admin/activities")
@RequiredArgsConstructor
public class AdminActivityController {

    private final ActivityManagementFacade activityManagementFacade;

    @GetMapping
    public String getActivities(Model model) {
        List<Activity> all = activityManagementFacade.listActivities();
        model.addAttribute("activities", all);
        model.addAttribute("activity", new Activity());
        model.addAttribute("referenceTypes", Activity.ReferenceField.values());
        return "admin/activities";
    }

    @PostMapping("/create")
    public String createActivity(@ModelAttribute Activity activity) {
        activityManagementFacade.saveActivity(activity);
        return "redirect:/admin/activities";
    }

    @PostMapping("/update")
    public String updateActivity(@ModelAttribute Activity activity) {
        activityManagementFacade.saveActivity(activity);
        return "redirect:/admin/activities";
    }

    @GetMapping("/edit/{id}")
    public String editActivity(@PathVariable String id, Model model) {
        Optional<Activity> byId = activityManagementFacade.findActivity(id);
        if (byId.isPresent()) {
            model.addAttribute("activity", byId.get());
            model.addAttribute("adminFormObject", byId.get());
            model.addAttribute("referenceTypes", Activity.ReferenceField.values());
            model.addAttribute("breadcrumbs", List.of(
                    new BreadcrumbItem("Activities", "/admin/activities"),
                    new BreadcrumbItem(byId.get().getName() == null ? "Edit Activity" : byId.get().getName())
            ));
            return "admin/activities-edit";
        } else {
            return "redirect:/admin/activities";
        }
    }

    @PostMapping("/duplicate/{id}")
    public String duplicateActivity(@PathVariable String id, Model model) {
        Optional<Activity> byId = activityManagementFacade.duplicateActivity(id);
        if (byId.isPresent()) {
            return "redirect:/admin/activities/edit/" + byId.get().getId();
        } else {
            return "redirect:/admin/activities";
        }
    }

    @PostMapping("/delete/{id}")
    public String deleteActivity(@PathVariable String id) {
        activityManagementFacade.deleteActivity(id);
        return "redirect:/admin/activities";
    }

    @GetMapping("/activityIndicators")
    public String redirectRetiredActivityIndicatorsList() {
        return "redirect:/admin/activities";
    }

    @GetMapping("/activityIndicators/edit/{id}")
    public String redirectRetiredActivityIndicatorsEdit(@PathVariable String id) {
        return "redirect:/admin/activities";
    }
}
