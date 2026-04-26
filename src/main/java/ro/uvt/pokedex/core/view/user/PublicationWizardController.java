package ro.uvt.pokedex.core.view.user;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/user/publications/add")
public class PublicationWizardController {

    @GetMapping({"", "/step1", "/step2", "/step3"})
    public String redirectToWorkspace() {
        return "redirect:/user/workspace#publications";
    }
}
