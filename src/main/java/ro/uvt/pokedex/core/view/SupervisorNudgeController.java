package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.notification.DirectedNotification.NudgeKind;
import ro.uvt.pokedex.core.service.application.NudgeService;

/**
 * Sends a supervisor's nudge to a researcher in their scope — the write end of the in-app nudge.
 * Authorization reuses {@code researcherAccess.canView}: if you may open this researcher's report,
 * you may nudge them. The nudge lands in the researcher's workspace bell.
 */
@Controller
@RequestMapping("/supervisor/nudge")
@RequiredArgsConstructor
public class SupervisorNudgeController {

    private final NudgeService nudgeService;

    @PostMapping
    @PreAuthorize("@researcherAccess.canView(#recipientEmail, authentication)")
    public String nudge(@RequestParam("recipientEmail") String recipientEmail,
                        @RequestParam("kind") NudgeKind kind,
                        @RequestParam(value = "note", required = false) String note,
                        @RequestParam(value = "returnUrl", required = false) String returnUrl,
                        Authentication authentication,
                        RedirectAttributes redirectAttributes) {
        String sender = authentication == null ? null : authentication.getName();
        nudgeService.nudge(sender, recipientEmail, kind, note);
        redirectAttributes.addFlashAttribute("successMessage",
                "Nudge sent to " + recipientEmail + ".");
        return "redirect:" + safeReturnUrl(returnUrl);
    }

    /** Only same-site relative paths are honored, to avoid an open redirect via the returnUrl param. */
    private static String safeReturnUrl(String returnUrl) {
        if (returnUrl != null && returnUrl.startsWith("/") && !returnUrl.startsWith("//")) {
            return returnUrl;
        }
        return "/supervisor";
    }
}
