package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ro.uvt.pokedex.core.service.application.UserDefinedTriageFacade;

@Controller
@RequestMapping("/admin/user-defined-triage")
@RequiredArgsConstructor
public class AdminUserDefinedTriageController {

    private final UserDefinedTriageFacade userDefinedTriageFacade;

    @GetMapping
    public String showTriagePage(Model model) {
        model.addAttribute("snapshot", userDefinedTriageFacade.snapshot(50, 50));
        model.addAttribute("sourceLinksDeepLink", "/admin/source-links?source=USER_DEFINED");
        model.addAttribute("conflictsDeepLink", "/admin/conflicts?incomingSource=USER_DEFINED");
        model.addAttribute("initializationDeepLink", "/admin/initialization");
        return "admin/user-defined-triage";
    }
}
