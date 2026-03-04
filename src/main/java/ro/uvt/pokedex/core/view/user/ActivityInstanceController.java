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
import ro.uvt.pokedex.core.repository.ActivityIndicatorRepository;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.service.ResearcherService;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/user/activityInstances")
@RequiredArgsConstructor
public class ActivityInstanceController {

    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityRepository activityRepository;
    private final ResearcherService researcherService;
    private final ActivityIndicatorRepository activityIndicatorRepository;

    @GetMapping
    public String getActivityInstances(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        String researcherId = currentUser.getResearcherId();

//        List<Publication> publications = publicationService.findPublicationsByResearcherIdOrderByYearDesc(researcherId);

        List<Activity> activities = activityRepository.findAll();
        model.addAttribute("activities", activities);
        model.addAttribute("referenceTypes", Activity.ReferenceField.values());
        ActivityInstance newInstance = new ActivityInstance();
        if(researcherId != null) {
            newInstance.setResearcherId(researcherId);
            List<ActivityInstance> all = activityInstanceRepository.findAllByResearcherId(researcherId);
            model.addAttribute("activityInstances", all);

            Iterator<ActivityInstance> iterator = all.iterator();
            while(iterator.hasNext()) {
                ActivityInstance instance = iterator.next();
                if(instance.getActivity() == null) {
                    activityInstanceRepository.deleteById(instance.getId());
                    iterator.remove();
                }
            }
            Map<String, List<ActivityInstance>> collect = all.stream().collect(Collectors.groupingBy(x -> x.getActivity().getName()));
            List<String> activityLabels = collect.keySet().stream().toList();

            List<Integer> activityData = activityLabels.stream().map(x -> collect.get(x).size()).collect(Collectors.toList()); // Fetch or compute data
            model.addAttribute("activityLabels", activityLabels);
            model.addAttribute("activityData", activityData);
        }
        model.addAttribute("newActivityInstance", newInstance);
        return "user/activity-instances";

    }

    @PostMapping("/create")
    public String createActivityInstance(@ModelAttribute ActivityInstance activityInstance) {

        activityInstanceRepository.save(activityInstance);
        return "redirect:/user/activityInstances";
    }

    @PostMapping("/update")
    public String updateActivityInstance(@ModelAttribute ActivityInstance activityInstance) {
        Optional<ActivityInstance> byId = activityInstanceRepository.findById(activityInstance.getId());
        if (byId.isPresent()) {
            ActivityInstance existingInstance = byId.get();
            existingInstance.setFields(activityInstance.getFields());
            existingInstance.setReferenceFields(activityInstance.getReferenceFields());
            activityInstanceRepository.save(existingInstance);
        }
        return "redirect:/user/activityInstances";
    }

    @GetMapping("/edit/{id}")
    public String editActivityInstance(@PathVariable String id, Model model) {
        Optional<ActivityInstance> byId = activityInstanceRepository.findById(id);
        if (byId.isPresent()) {
            model.addAttribute("activityInstance", byId.get());
            return "user/activity-instances-edit";
        } else {
            return "redirect:/user/activityInstances";
        }
    }

    @PostMapping("/delete/{id}")
    public String deleteActivityInstance(@PathVariable String id) {
        activityInstanceRepository.deleteById(id);
        return "redirect:/user/activityInstances";
    }

    @GetMapping("/activity/{id}/fields")
    @ResponseBody
    public ResponseEntity<Activity> getActivityFields(@PathVariable String id) {
        Optional<Activity> activity = activityRepository.findById(id);
        return activity.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }


}
