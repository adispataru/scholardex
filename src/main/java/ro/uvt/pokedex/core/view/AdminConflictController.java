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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Controller
@RequestMapping("/admin/conflicts")
@RequiredArgsConstructor
public class AdminConflictController {

    private final ConflictOperationsFacade conflictOperationsFacade;

    @GetMapping
    public String showConflictsPage(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "entityType", required = false) String entityType,
            @RequestParam(name = "incomingSource", required = false) String incomingSource,
            @RequestParam(name = "reasonCode", required = false) String reasonCode,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "detectedFrom", required = false) String detectedFrom,
            @RequestParam(name = "detectedTo", required = false) String detectedTo,
            Model model
    ) {
        Instant from = parseDateStart(detectedFrom);
        Instant to = parseDateEnd(detectedTo);
        model.addAttribute("identityPageData", conflictOperationsFacade.findIdentityConflicts(
                page, size, entityType, incomingSource, reasonCode, status, from, to
        ));
        model.addAttribute("summary", conflictOperationsFacade.summarizeIdentityConflicts());
        model.addAttribute("entityType", normalize(entityType));
        model.addAttribute("incomingSource", normalize(incomingSource));
        model.addAttribute("reasonCode", normalize(reasonCode));
        model.addAttribute("status", normalize(status));
        model.addAttribute("detectedFrom", normalize(detectedFrom));
        model.addAttribute("detectedTo", normalize(detectedTo));

        return "admin/conflicts";
    }

    @PostMapping("/resolve")
    public String resolveConflict(
            @RequestParam(name = "id") String id,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        long updated = conflictOperationsFacade.updateConflictStatus(id, "RESOLVED", authentication == null ? "" : authentication.getName());
        if (updated == 0L) {
            redirectAttributes.addFlashAttribute("errorMessage", "Conflict resolve skipped. Conflict is missing or not OPEN.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "Conflict resolved.");
        }
        return "redirect:/admin/conflicts";
    }

    @PostMapping("/dismiss")
    public String dismissConflict(
            @RequestParam(name = "id") String id,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        long updated = conflictOperationsFacade.updateConflictStatus(id, "DISMISSED", authentication == null ? "" : authentication.getName());
        if (updated == 0L) {
            redirectAttributes.addFlashAttribute("errorMessage", "Conflict dismiss skipped. Conflict is missing or not OPEN.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "Conflict dismissed.");
        }
        return "redirect:/admin/conflicts";
    }

    @PostMapping("/bulkStatus")
    public String bulkUpdateConflicts(
            @RequestParam(name = "ids", required = false) List<String> ids,
            @RequestParam(name = "singleId", required = false) String singleId,
            @RequestParam(name = "action", required = false) String action,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        if ("resolveOne".equalsIgnoreCase(action) && singleId != null) {
            long updated = conflictOperationsFacade.updateConflictStatus(singleId, "RESOLVED", authentication == null ? "" : authentication.getName());
            redirectAttributes.addFlashAttribute("successMessage", "Conflict resolve requested. updated=" + updated + ".");
            return "redirect:/admin/conflicts";
        }
        if ("dismissOne".equalsIgnoreCase(action) && singleId != null) {
            long updated = conflictOperationsFacade.updateConflictStatus(singleId, "DISMISSED", authentication == null ? "" : authentication.getName());
            redirectAttributes.addFlashAttribute("successMessage", "Conflict dismiss requested. updated=" + updated + ".");
            return "redirect:/admin/conflicts";
        }
        String requestedStatus = "dismiss".equalsIgnoreCase(action) ? "DISMISSED" : "RESOLVED";
        long updated = conflictOperationsFacade.bulkUpdateConflictStatus(ids, requestedStatus, authentication == null ? "" : authentication.getName());
        redirectAttributes.addFlashAttribute("successMessage", "Bulk conflict update complete. updated=" + updated + ".");
        return "redirect:/admin/conflicts";
    }

    @PostMapping("/identity/open/clear")
    public String clearOpenIdentityConflicts(
            @RequestParam(name = "confirmation", required = false) String confirmation,
            RedirectAttributes redirectAttributes
    ) {
        if (!isResetConfirmation(confirmation)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Open identity conflict clear aborted. Type RESET in the confirmation field to proceed.");
            return "redirect:/admin/conflicts";
        }
        long deleted = conflictOperationsFacade.clearOpenIdentityConflicts();
        redirectAttributes.addFlashAttribute("successMessage",
                "Open identity conflicts cleared. deleted=" + deleted + ".");
        return "redirect:/admin/conflicts";
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

    private Instant parseDateStart(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return LocalDate.parse(raw.trim()).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception ex) {
            return null;
        }
    }

    private Instant parseDateEnd(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return LocalDate.parse(raw.trim()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception ex) {
            return null;
        }
    }
}
