package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.service.application.ConflictOperationsFacade;

@Controller
@RequestMapping("/admin/conflicts")
@RequiredArgsConstructor
public class AdminConflictController {

    private final ConflictOperationsFacade conflictOperationsFacade;

    @GetMapping
    public String showConflictsPage(
            @RequestParam(name = "wosIdentityPage", required = false) Integer wosIdentityPage,
            @RequestParam(name = "wosIdentitySize", required = false) Integer wosIdentitySize,
            @RequestParam(name = "wosIdentitySourceVersion", required = false) String wosIdentitySourceVersion,
            @RequestParam(name = "wosIdentitySourceFile", required = false) String wosIdentitySourceFile,
            @RequestParam(name = "wosIdentityConflictType", required = false) String wosIdentityConflictType,
            @RequestParam(name = "wosFactPage", required = false) Integer wosFactPage,
            @RequestParam(name = "wosFactSize", required = false) Integer wosFactSize,
            @RequestParam(name = "wosFactSourceVersion", required = false) String wosFactSourceVersion,
            @RequestParam(name = "wosFactType", required = false) String wosFactType,
            @RequestParam(name = "wosFactConflictReason", required = false) String wosFactConflictReason,
            @RequestParam(name = "scopusLinkPage", required = false) Integer scopusLinkPage,
            @RequestParam(name = "scopusLinkSize", required = false) Integer scopusLinkSize,
            @RequestParam(name = "scopusLinkEnrichmentSource", required = false) String scopusLinkEnrichmentSource,
            @RequestParam(name = "scopusLinkKeyType", required = false) String scopusLinkKeyType,
            @RequestParam(name = "scopusLinkConflictReason", required = false) String scopusLinkConflictReason,
            Model model
    ) {
        model.addAttribute("wosIdentityPageData", conflictOperationsFacade.findWosIdentityConflicts(
                wosIdentityPage, wosIdentitySize, wosIdentitySourceVersion, wosIdentitySourceFile, wosIdentityConflictType
        ));
        model.addAttribute("wosFactPageData", conflictOperationsFacade.findWosFactConflicts(
                wosFactPage, wosFactSize, wosFactSourceVersion, wosFactType, wosFactConflictReason
        ));
        model.addAttribute("scopusLinkPageData", conflictOperationsFacade.findScopusLinkConflicts(
                scopusLinkPage, scopusLinkSize, scopusLinkEnrichmentSource, scopusLinkKeyType, scopusLinkConflictReason
        ));

        model.addAttribute("wosIdentitySourceVersion", normalize(wosIdentitySourceVersion));
        model.addAttribute("wosIdentitySourceFile", normalize(wosIdentitySourceFile));
        model.addAttribute("wosIdentityConflictType", normalize(wosIdentityConflictType));

        model.addAttribute("wosFactSourceVersion", normalize(wosFactSourceVersion));
        model.addAttribute("wosFactType", normalize(wosFactType));
        model.addAttribute("wosFactConflictReason", normalize(wosFactConflictReason));

        model.addAttribute("scopusLinkEnrichmentSource", normalize(scopusLinkEnrichmentSource));
        model.addAttribute("scopusLinkKeyType", normalize(scopusLinkKeyType));
        model.addAttribute("scopusLinkConflictReason", normalize(scopusLinkConflictReason));

        return "admin/conflicts";
    }

    @PostMapping("/wos/identity/clear")
    public String clearWosIdentityConflicts(
            @RequestParam(name = "confirmation", required = false) String confirmation,
            RedirectAttributes redirectAttributes
    ) {
        if (!isResetConfirmation(confirmation)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "WoS identity conflict clear aborted. Type RESET in the confirmation field to proceed.");
            return "redirect:/admin/conflicts";
        }
        long deleted = conflictOperationsFacade.clearWosIdentityConflicts();
        redirectAttributes.addFlashAttribute("successMessage",
                "WoS identity conflicts cleared. deleted=" + deleted + ".");
        return "redirect:/admin/conflicts";
    }

    @PostMapping("/wos/fact/clear")
    public String clearWosFactConflicts(
            @RequestParam(name = "confirmation", required = false) String confirmation,
            RedirectAttributes redirectAttributes
    ) {
        if (!isResetConfirmation(confirmation)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "WoS fact conflict clear aborted. Type RESET in the confirmation field to proceed.");
            return "redirect:/admin/conflicts";
        }
        long deleted = conflictOperationsFacade.clearWosFactConflicts();
        redirectAttributes.addFlashAttribute("successMessage",
                "WoS fact conflicts cleared. deleted=" + deleted + ".");
        return "redirect:/admin/conflicts";
    }

    @PostMapping("/scopus/link/clear")
    public String clearScopusLinkConflicts(
            @RequestParam(name = "confirmation", required = false) String confirmation,
            RedirectAttributes redirectAttributes
    ) {
        if (!isResetConfirmation(confirmation)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Scopus link conflict clear aborted. Type RESET in the confirmation field to proceed.");
            return "redirect:/admin/conflicts";
        }
        long deleted = conflictOperationsFacade.clearScopusLinkConflicts();
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus link conflicts cleared. deleted=" + deleted + ".");
        return "redirect:/admin/conflicts";
    }

    private boolean isResetConfirmation(String confirmation) {
        return "RESET".equals(confirmation == null ? null : confirmation.trim());
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
