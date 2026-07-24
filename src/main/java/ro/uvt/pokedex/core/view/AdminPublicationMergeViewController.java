package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.service.application.PublicationMergeAdminFacade;

import java.security.Principal;

/**
 * H84 S2 — the admin publication-merge queue page: pending requests with a side-by-side compare and
 * approve/reject actions, the applied-merge history (lastAppliedAt = rebuild re-apply observability), and a
 * manual direct-merge form for known duplicate pairs. JSON automation lives in
 * {@link AdminPublicationMergeController}; this controller is the human surface over the same facade.
 */
@Controller
@RequestMapping("/admin/publication-merges")
@RequiredArgsConstructor
public class AdminPublicationMergeViewController {

    private final PublicationMergeAdminFacade publicationMergeAdminFacade;

    @GetMapping
    public String showQueue(Model model) {
        model.addAttribute("queue", publicationMergeAdminFacade.queue());
        return "admin/publication-merges";
    }

    @PostMapping("/merge")
    public String directMerge(@RequestParam String survivorId,
                              @RequestParam String duplicateId,
                              @RequestParam(required = false) String note,
                              @RequestParam(defaultValue = "false") boolean rebuildNow,
                              Principal principal,
                              RedirectAttributes redirectAttributes) {
        try {
            flash(redirectAttributes, publicationMergeAdminFacade.directMerge(
                    survivorId.trim(), duplicateId.trim(), principalName(principal), note, rebuildNow));
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/publication-merges";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable String id,
                          @RequestParam(required = false) String note,
                          @RequestParam(defaultValue = "false") boolean swapSides,
                          @RequestParam(defaultValue = "false") boolean rebuildNow,
                          Principal principal,
                          RedirectAttributes redirectAttributes) {
        try {
            flash(redirectAttributes, publicationMergeAdminFacade.approve(
                    id, principalName(principal), note, swapSides, rebuildNow));
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/publication-merges";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable String id,
                         @RequestParam(required = false) String note,
                         Principal principal,
                         RedirectAttributes redirectAttributes) {
        try {
            publicationMergeAdminFacade.reject(id, principalName(principal), note);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Request rejected — the pair will not be suggested again.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/publication-merges";
    }

    private static void flash(RedirectAttributes redirectAttributes,
                              PublicationMergeAdminFacade.OperationResult result) {
        redirectAttributes.addFlashAttribute(result.success() ? "successMessage" : "errorMessage", result.message());
    }

    private static String principalName(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
