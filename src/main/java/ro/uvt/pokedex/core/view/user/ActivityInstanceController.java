package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade;

import java.util.Optional;

/**
 * The old activity pages, now redirects to the workspace. H145: their write routes are gone — they bound the whole
 * record from the request (owner, id, a head's approval); records are written only through the workspace's endpoints.
 */
@Controller
@RequestMapping("/user/activities")
@RequiredArgsConstructor
public class ActivityInstanceController {

    private final UserActivityInstanceFacade userActivityInstanceFacade;

    @GetMapping
    public String getActivityInstances() {
        return "redirect:/user/workspace#activities";
    }

    @GetMapping("/edit/{id}")
    public String editActivityInstance() {
        return "redirect:/user/workspace#activities";
    }

    @GetMapping("/activity/{id}/fields")
    @ResponseBody
    public ResponseEntity<Activity> getActivityFields(@PathVariable String id) {
        Optional<Activity> activity = userActivityInstanceFacade.findActivity(id);
        return activity.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }


}
