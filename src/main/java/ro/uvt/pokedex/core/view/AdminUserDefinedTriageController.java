package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.service.application.ConflictOperationsFacade;
import ro.uvt.pokedex.core.service.application.UserDefinedTriageFacade;

import java.util.List;

@Controller
@RequestMapping("/admin/user-defined-triage")
@RequiredArgsConstructor
public class AdminUserDefinedTriageController {

    private final UserDefinedTriageFacade userDefinedTriageFacade;
    private final ConflictOperationsFacade conflictOperationsFacade;

    @GetMapping
    public String showTriagePage(
            @RequestParam(name = "triagePage", required = false) Integer triagePage,
            @RequestParam(name = "triageSize", required = false) Integer triageSize,
            Model model
    ) {
        model.addAttribute("snapshot", userDefinedTriageFacade.snapshot(50, 50));
        model.addAttribute("triageQueue", conflictOperationsFacade.findNeedsReviewIdentityConflicts(
                triagePage, triageSize, null, "USER_DEFINED", null, "OPEN", null, null
        ));
        model.addAttribute("triagePage", triagePage == null ? 0 : triagePage);
        model.addAttribute("sourceLinksDeepLink", "/admin/source-links?source=USER_DEFINED");
        model.addAttribute("conflictsDeepLink", "/admin/conflicts?incomingSource=USER_DEFINED");
        model.addAttribute("initializationDeepLink", "/admin/initialization");
        return "admin/user-defined-triage";
    }

    @PostMapping("/conflict/resolve")
    public String resolveTriageConflict(
            @RequestParam(name = "id") String id,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        long updated = conflictOperationsFacade.updateConflictStatus(id, "RESOLVED", authentication == null ? "" : authentication.getName());
        if (updated == 0L) {
            redirectAttributes.addFlashAttribute("errorMessage", "Resolve skipped — conflict not found or already closed.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "Conflict resolved.");
        }
        return "redirect:/admin/user-defined-triage";
    }

    @PostMapping("/conflict/dismiss")
    public String dismissTriageConflict(
            @RequestParam(name = "id") String id,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        long updated = conflictOperationsFacade.updateConflictStatus(id, "DISMISSED", authentication == null ? "" : authentication.getName());
        if (updated == 0L) {
            redirectAttributes.addFlashAttribute("errorMessage", "Dismiss skipped — conflict not found or already closed.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "Conflict dismissed.");
        }
        return "redirect:/admin/user-defined-triage";
    }

    @PostMapping("/conflict/investigate")
    public String investigateTriageConflict(
            @RequestParam(name = "id") String id,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        long updated = conflictOperationsFacade.updateConflictStatus(id, "INVESTIGATED", authentication == null ? "" : authentication.getName());
        if (updated == 0L) {
            redirectAttributes.addFlashAttribute("errorMessage", "Investigate skipped — conflict not found or already closed.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "Conflict marked as under investigation.");
        }
        return "redirect:/admin/user-defined-triage";
    }

    @PostMapping("/conflict/bulk")
    public String bulkTriageConflicts(
            @RequestParam(name = "ids", required = false) List<String> ids,
            @RequestParam(name = "action", required = false) String action,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        String operator = authentication == null ? "" : authentication.getName();
        String requestedStatus = "dismiss".equalsIgnoreCase(action) ? "DISMISSED"
                : "investigate".equalsIgnoreCase(action) ? "INVESTIGATED"
                : "RESOLVED";
        long updated = conflictOperationsFacade.bulkUpdateConflictStatus(ids, requestedStatus, operator);
        int total = ids == null ? 0 : ids.size();
        redirectAttributes.addFlashAttribute("successMessage",
                "Bulk update complete — " + requestedStatus.toLowerCase() + " " + updated + " of " + total + " selected.");
        return "redirect:/admin/user-defined-triage";
    }
}
