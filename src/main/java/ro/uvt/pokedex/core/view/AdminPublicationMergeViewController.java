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
import ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade;

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
    private final PublicationVenueClaimAdminFacade venueClaimAdminFacade;

    @GetMapping
    public String showQueue(Model model) {
        model.addAttribute("queue", publicationMergeAdminFacade.queue());
        // H93 S2: the venue-claims half of the same page — one review surface, not a second thing to watch.
        model.addAttribute("claimQueue", venueClaimAdminFacade.queue());
        return "admin/publication-merges";
    }

    /* ── H93: venue-claim actions (the claims half of this page) ─────────────── */

    @PostMapping("/venue-claim")
    public String directVenueClaim(@RequestParam String publicationId,
                                   @RequestParam String forumId,
                                   @RequestParam(defaultValue = "false") boolean workshopOf,
                                   @RequestParam(required = false) String workshopLabel,
                                   @RequestParam(required = false) String note,
                                   @RequestParam(defaultValue = "false") boolean rebuildNow,
                                   Principal principal,
                                   RedirectAttributes redirectAttributes) {
        try {
            flash(redirectAttributes, venueClaimAdminFacade.directClaim(
                    publicationId.trim(), forumId.trim(), workshopOf, workshopLabel,
                    principalName(principal), note, rebuildNow));
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/publication-merges";
    }

    @PostMapping("/venue-claims/{id}/approve")
    public String approveVenueClaim(@PathVariable String id,
                                    @RequestParam(required = false) String note,
                                    @RequestParam(defaultValue = "false") boolean rebuildNow,
                                    Principal principal,
                                    RedirectAttributes redirectAttributes) {
        try {
            flash(redirectAttributes, venueClaimAdminFacade.approve(id, principalName(principal), note, rebuildNow));
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/publication-merges";
    }

    @PostMapping("/venue-claims/{id}/reject")
    public String rejectVenueClaim(@PathVariable String id,
                                   @RequestParam(required = false) String note,
                                   Principal principal,
                                   RedirectAttributes redirectAttributes) {
        try {
            venueClaimAdminFacade.reject(id, principalName(principal), note);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Claim rejected — anything it had applied has been reverted.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/publication-merges";
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

    private static void flash(RedirectAttributes redirectAttributes,
                              PublicationVenueClaimAdminFacade.OperationResult result) {
        redirectAttributes.addFlashAttribute(result.success() ? "successMessage" : "errorMessage", result.message());
    }

    private static String principalName(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
