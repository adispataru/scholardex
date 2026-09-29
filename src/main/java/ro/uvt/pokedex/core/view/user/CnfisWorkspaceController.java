package ro.uvt.pokedex.core.view.user;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.reporting.CnfisEdition;

import java.util.Comparator;

/**
 * H129 — the CNFIS entry of the sidebar: the editions of the reporting and, for each, the sheets a person
 * hands in. For now the page offers the editions and the download of Anexa 5; the preview of the sheet, the
 * domain, the CNATDCU score and the frozen copies come with the next slice.
 */
@Controller
@RequestMapping("/user/cnfis")
public class CnfisWorkspaceController {

    @GetMapping
    public String showCnfis(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }
        model.addAttribute("user", currentUser);
        model.addAttribute("editions", CnfisEdition.known().stream()
                .sorted(Comparator.comparingInt(CnfisEdition::reportingYear).reversed())
                .toList());
        return "user/cnfis";
    }
}
