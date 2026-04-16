package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade;

import java.util.Optional;

@Controller
@RequestMapping("/user/activities")
@RequiredArgsConstructor
public class ActivityInstanceController {

    private final UserActivityInstanceFacade userActivityInstanceFacade;

    @GetMapping
    public String getActivityInstances() {
        return "redirect:/user/workspace#activities";
    }

    @PostMapping("/create")
    public String createActivityInstance(@ModelAttribute ActivityInstance activityInstance) {

        userActivityInstanceFacade.saveActivityInstance(activityInstance);
        return "redirect:/user/activities";
    }

    @PostMapping("/update")
    public String updateActivityInstance(@ModelAttribute ActivityInstance activityInstance) {
        userActivityInstanceFacade.updateActivityInstance(activityInstance);
        return "redirect:/user/activities";
    }

    @GetMapping("/edit/{id}")
    public String editActivityInstance() {
        return "redirect:/user/workspace#activities";
    }

    @PostMapping("/delete/{id}")
    public String deleteActivityInstance(@PathVariable String id) {
        userActivityInstanceFacade.deleteActivityInstance(id);
        return "redirect:/user/activities";
    }

    @GetMapping("/activity/{id}/fields")
    @ResponseBody
    public ResponseEntity<Activity> getActivityFields(@PathVariable String id) {
        Optional<Activity> activity = userActivityInstanceFacade.findActivity(id);
        return activity.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }


}
