package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.ActivityIndicator;
import ro.uvt.pokedex.core.service.application.ActivityManagementFacade;

import java.util.List;
import java.util.Map;
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
            model.addAttribute("referenceTypes", Activity.ReferenceField.values());
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

    // New endpoints for ActivityIndicator

    @GetMapping("/activityIndicators")
    public String getActivityIndicators(Model model) {
        List<ActivityIndicator> indicators = activityManagementFacade.listActivityIndicators();
        List<Activity> activities = activityManagementFacade.listActivities();
        model.addAttribute("activities", activities);
        model.addAttribute("indicators", indicators);
        model.addAttribute("activityIndicator", new ActivityIndicator());
        Map<String, String> activityDescriptions = activityManagementFacade.buildActivityDescriptions(activities);
        model.addAttribute("activityDescriptions", activityDescriptions);
        return "admin/activity-indicators";
    }

    @PostMapping("/activityIndicators/create")
    public String createActivityIndicator(@ModelAttribute ActivityIndicator activityIndicator) {
        activityManagementFacade.saveActivityIndicator(activityIndicator);
        return "redirect:/admin/activityIndicators";
    }

    @PostMapping("/activityIndicators/update")
    public String updateActivityIndicator(@ModelAttribute ActivityIndicator activityIndicator) {
        activityManagementFacade.saveActivityIndicator(activityIndicator);
        return "redirect:/admin/activityIndicators";
    }

    @GetMapping("/activityIndicators/edit/{id}")
    public String editActivityIndicator(@PathVariable String id, Model model) {
        Optional<ActivityIndicator> indicator = activityManagementFacade.findActivityIndicator(id);
        if (indicator.isPresent()) {
            List<Activity> activities = activityManagementFacade.listActivities();
            model.addAttribute("activityIndicator", indicator.get());
            model.addAttribute("activities", activities);
            return "admin/activity-indicator-edit";
        } else {
            return "redirect:/admin/activityIndicators";
        }
    }

    @PostMapping("/activityIndicators/delete/{id}")
    public String deleteActivityIndicator(@PathVariable String id) {
        activityManagementFacade.deleteActivityIndicator(id);
        return "redirect:/admin/activityIndicators";
    }
}
