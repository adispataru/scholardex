package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.ActivityIndicator;
import ro.uvt.pokedex.core.repository.ActivityIndicatorRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/activities")
@RequiredArgsConstructor
public class AdminActivityController {


    private final ActivityRepository activityRepository;
    private final ActivityIndicatorRepository activityIndicatorRepository;

    @GetMapping
    public String getActivities(Model model) {
        List<Activity> all = activityRepository.findAll();
        model.addAttribute("activities", all);
        model.addAttribute("activity", new Activity());
        model.addAttribute("referenceTypes", Activity.ReferenceField.values());
        return "admin/activities";
    }

    @PostMapping("/create")
    public String createActivity(@ModelAttribute Activity activity) {
        activityRepository.save(activity);
        return "redirect:/admin/activities";
    }

    @PostMapping("/update")
    public String updateActivity(@ModelAttribute Activity activity) {
        activityRepository.save(activity);
        return "redirect:/admin/activities";
    }

    @GetMapping("/edit/{id}")
    public String editActivity(@PathVariable String id, Model model) {
        Optional<Activity> byId = activityRepository.findById(id);
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
        Optional<Activity> byId = activityRepository.findById(id);
        if (byId.isPresent()) {
            Activity activity = byId.get();
            activity.setId(null);
            activity.setName(activity.getName() + " (copy)");
            activity = activityRepository.save(activity);
            return "redirect:/admin/activities/edit/" + activity.getId();
        } else {
            return "redirect:/admin/activities";
        }
    }

    @PostMapping("/delete/{id}")
    public String deleteActivity(@PathVariable String id) {
        activityRepository.deleteById(id);
        return "redirect:/admin/activities";
    }

    // New endpoints for ActivityIndicator

    @GetMapping("/activityIndicators")
    public String getActivityIndicators(Model model) {
        List<ActivityIndicator> indicators = activityIndicatorRepository.findAll();
        List<Activity> activities = activityRepository.findAll();
        model.addAttribute("activities", activities);
        model.addAttribute("indicators", indicators);
        model.addAttribute("activityIndicator", new ActivityIndicator());
        // build a map: activityId -> "Fields: name1 (numeric); name2 [a,b]"
        Map<String,String> activityDescriptions = activities.stream()
                .collect(Collectors.toMap(
                        Activity::getId,
                        act -> {
                            String body = act.getFields().stream()
                                    .map(f -> f.getName()
                                            + (f.isNumber()
                                            ? " (numeric)"
                                            : f.getAllowedValues() != null ? " [" + String.join(", ", f.getAllowedValues()) + "]": ""))
                                    .collect(Collectors.joining("; "));
                            return "Fields: " + body;
                        }
                ));
        model.addAttribute("activityDescriptions", activityDescriptions);
        return "admin/activity-indicators";
    }

    @PostMapping("/activityIndicators/create")
    public String createActivityIndicator(@ModelAttribute ActivityIndicator activityIndicator) {
        activityIndicatorRepository.save(activityIndicator);
        return "redirect:/admin/activityIndicators";
    }

    @PostMapping("/activityIndicators/update")
    public String updateActivityIndicator(@ModelAttribute ActivityIndicator activityIndicator) {
        activityIndicatorRepository.save(activityIndicator);
        return "redirect:/admin/activityIndicators";
    }

    @GetMapping("/activityIndicators/edit/{id}")
    public String editActivityIndicator(@PathVariable String id, Model model) {
        Optional<ActivityIndicator> indicator = activityIndicatorRepository.findById(id);
        if (indicator.isPresent()) {
            List<Activity> activities = activityRepository.findAll();
            model.addAttribute("activityIndicator", indicator.get());
            model.addAttribute("activities", activities);
            return "admin/activity-indicator-edit";
        } else {
            return "redirect:/admin/activityIndicators";
        }
    }

    @PostMapping("/activityIndicators/delete/{id}")
    public String deleteActivityIndicator(@PathVariable String id) {
        activityIndicatorRepository.deleteById(id);
        return "redirect:/admin/activityIndicators";
    }
}
