package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService;
import ro.uvt.pokedex.core.service.application.PublisherClaimReviewService;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.DeclarationException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Where a head of a department or faculty, or a platform admin, decides on the principal-authorship
 * declarations of the researchers they answer for. The URL rule lets supervisors and admins in; which
 * declarations each of them sees and may decide is the service's per-researcher check.
 */
@Controller
@RequestMapping("/supervisor/declarations")
@RequiredArgsConstructor
public class PrincipalAuthorDeclarationReviewController {

    private static final String PAGE = "redirect:/supervisor/declarations";
    private static final int DECIDED_SHOWN = 100;
    private static final java.time.format.DateTimeFormatter WHEN = java.time.format.DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.of("Europe/Bucharest"));

    private final PrincipalAuthorDeclarationService declarations;
    private final UserService userService;
    /** H143 — requests to classify the publisher of a declared book, decided on the same page. */
    private final PublisherClaimReviewService publisherClaims;

    @GetMapping
    public String page(Authentication authentication, Model model) {
        List<PrincipalAuthorDeclaration> pending = declarations.pendingFor(authentication);
        List<PrincipalAuthorDeclaration> decided = declarations.decidedFor(authentication, DECIDED_SHOWN);
        Set<String> people = new LinkedHashSet<>();
        pending.forEach(d -> people.add(d.getUserEmail()));
        decided.forEach(d -> {
            people.add(d.getUserEmail());
            if (d.getDecidedBy() != null) {
                people.add(d.getDecidedBy());
            }
        });
        List<PublisherClaimReviewService.ClaimItem> claimsPending = publisherClaims.pendingFor(authentication);
        List<PublisherClaimReviewService.ClaimItem> claimsDecided = publisherClaims.decidedFor(authentication, DECIDED_SHOWN);
        claimsPending.forEach(c -> people.add(c.researcherEmail()));
        claimsDecided.forEach(c -> {
            people.add(c.researcherEmail());
            if (c.record() != null && c.record().claim() != null && c.record().claim().decidedBy() != null) {
                people.add(c.record().claim().decidedBy());
            }
        });
        model.addAttribute("pending", pending);
        model.addAttribute("decided", decided);
        model.addAttribute("claimsPending", claimsPending);
        model.addAttribute("claimsDecided", claimsDecided);
        model.addAttribute("names", userService.findDisplayLabels(List.copyOf(people)));
        java.util.Map<String, String> decidedOn = new java.util.HashMap<>();
        decided.forEach(d -> decidedOn.put(d.getId(), d.getDecidedAt() == null ? "" : WHEN.format(d.getDecidedAt())));
        claimsDecided.forEach(c -> decidedOn.put(c.activityId(),
                c.record() == null || c.record().claim() == null || c.record().claim().decidedAt() == null
                        ? "" : WHEN.format(c.record().claim().decidedAt())));
        model.addAttribute("decidedOn", decidedOn);
        return "supervisor/declarations";
    }

    /** H143 — approve a researcher's request for a publisher category; it counts from the next run. */
    @PostMapping("/publisher-claims/{activityId}/approve")
    public String approveClaim(@PathVariable String activityId, @RequestParam(name = "note", required = false) String note,
                               Authentication authentication, RedirectAttributes redirect) {
        return decideClaim(redirect, "claimApproved", () -> publisherClaims.approve(activityId, authentication, note));
    }

    @PostMapping("/publisher-claims/{activityId}/revoke")
    public String revokeClaim(@PathVariable String activityId, @RequestParam(name = "note", required = false) String note,
                              Authentication authentication, RedirectAttributes redirect) {
        return decideClaim(redirect, "revoked", () -> publisherClaims.revoke(activityId, authentication, note));
    }

    @PostMapping("/publisher-claims/{activityId}/reject")
    public String rejectClaim(@PathVariable String activityId, @RequestParam(name = "note", required = false) String note,
                              Authentication authentication, RedirectAttributes redirect) {
        return decideClaim(redirect, "claimRejected", () -> publisherClaims.reject(activityId, authentication, note));
    }

    private static String decideClaim(RedirectAttributes redirect, String done, Supplier<?> decision) {
        try {
            decision.get();
            redirect.addFlashAttribute("doneKey", "supervisor.declarations.done." + done);
        } catch (PublisherClaimReviewService.ClaimRefused refused) {
            redirect.addFlashAttribute("refusedKey", "supervisor.claims.refused." + refused.refusal().name());
        }
        return PAGE + "#publisher-claims";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable String id, @RequestParam(name = "note", required = false) String note,
                          Authentication authentication, RedirectAttributes redirect) {
        return decide(redirect, "approved", () -> declarations.approve(id, authentication, note));
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable String id, @RequestParam(name = "note", required = false) String note,
                         Authentication authentication, RedirectAttributes redirect) {
        return decide(redirect, "rejected", () -> declarations.reject(id, authentication, note));
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable String id, @RequestParam(name = "note", required = false) String note,
                         Authentication authentication, RedirectAttributes redirect) {
        return decide(redirect, "revoked", () -> declarations.revoke(id, authentication, note));
    }

    /** The flash attributes carry message KEYS; the page resolves them in the reader's language. */
    private static String decide(RedirectAttributes redirect, String done,
                                 Supplier<PrincipalAuthorDeclaration> decision) {
        try {
            decision.get();
            redirect.addFlashAttribute("doneKey", "supervisor.declarations.done." + done);
        } catch (DeclarationException refused) {
            redirect.addFlashAttribute("refusedKey", "supervisor.declarations.refused." + refused.refusal().name());
        }
        return PAGE;
    }
}
