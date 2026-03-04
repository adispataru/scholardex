package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade;
import ro.uvt.pokedex.core.service.application.model.UserActivityInstancesViewModel;

import java.util.Optional;

@Controller
@RequestMapping("/user/activityInstances")
@RequiredArgsConstructor
public class ActivityInstanceController {

    private final UserActivityInstanceFacade userActivityInstanceFacade;

    @GetMapping
    public String getActivityInstances(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        String researcherId = currentUser.getResearcherId();

        UserActivityInstancesViewModel viewModel = userActivityInstanceFacade.buildActivityInstancesView(researcherId);
        model.addAttribute("activities", viewModel.activities());
        model.addAttribute("referenceTypes", viewModel.referenceTypes());
        model.addAttribute("activityInstances", viewModel.activityInstances());
        model.addAttribute("activityLabels", viewModel.activityLabels());
        model.addAttribute("activityData", viewModel.activityData());
        model.addAttribute("newActivityInstance", viewModel.newActivityInstance());
        return "user/activity-instances";

    }

    @PostMapping("/create")
    public String createActivityInstance(@ModelAttribute ActivityInstance activityInstance) {

        userActivityInstanceFacade.saveActivityInstance(activityInstance);
        return "redirect:/user/activityInstances";
    }

    @PostMapping("/update")
    public String updateActivityInstance(@ModelAttribute ActivityInstance activityInstance) {
        userActivityInstanceFacade.updateActivityInstance(activityInstance);
        return "redirect:/user/activityInstances";
    }

    @GetMapping("/edit/{id}")
    public String editActivityInstance(@PathVariable String id, Model model) {
        Optional<ActivityInstance> byId = userActivityInstanceFacade.findActivityInstance(id);
        if (byId.isPresent()) {
            model.addAttribute("activityInstance", byId.get());
            return "user/activity-instances-edit";
        } else {
            return "redirect:/user/activityInstances";
        }
    }

    @PostMapping("/delete/{id}")
    public String deleteActivityInstance(@PathVariable String id) {
        userActivityInstanceFacade.deleteActivityInstance(id);
        return "redirect:/user/activityInstances";
    }

    @GetMapping("/activity/{id}/fields")
    @ResponseBody
    public ResponseEntity<Activity> getActivityFields(@PathVariable String id) {
        Optional<Activity> activity = userActivityInstanceFacade.findActivity(id);
        return activity.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }


}
