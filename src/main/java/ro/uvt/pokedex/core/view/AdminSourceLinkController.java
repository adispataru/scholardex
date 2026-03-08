package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.service.application.SourceLinkOperationsFacade;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Controller
@RequestMapping("/admin/source-links")
@RequiredArgsConstructor
public class AdminSourceLinkController {

    private final SourceLinkOperationsFacade sourceLinkOperationsFacade;

    @GetMapping
    public String showSourceLinks(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "entityType", required = false) String entityType,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "linkState", required = false) String linkState,
            @RequestParam(name = "sourceBatchId", required = false) String sourceBatchId,
            @RequestParam(name = "sourceCorrelationId", required = false) String sourceCorrelationId,
            @RequestParam(name = "sourceEventId", required = false) String sourceEventId,
            @RequestParam(name = "updatedFrom", required = false) String updatedFromRaw,
            @RequestParam(name = "updatedTo", required = false) String updatedToRaw,
            @RequestParam(name = "lookupSourceRecordId", required = false) String lookupSourceRecordId,
            @RequestParam(name = "lookupCanonicalId", required = false) String lookupCanonicalId,
            Model model
    ) {
        Instant updatedFrom = parseDateStart(updatedFromRaw);
        Instant updatedTo = parseDateEnd(updatedToRaw);
        model.addAttribute("pageData", sourceLinkOperationsFacade.findSourceLinks(
                page, size, entityType, source, linkState, sourceBatchId, sourceCorrelationId, sourceEventId, updatedFrom, updatedTo
        ));
        model.addAttribute("summary", sourceLinkOperationsFacade.replayEligibilitySummary());
        model.addAttribute("entityType", trim(entityType));
        model.addAttribute("source", trim(source));
        model.addAttribute("linkState", trim(linkState));
        model.addAttribute("sourceBatchId", trim(sourceBatchId));
        model.addAttribute("sourceCorrelationId", trim(sourceCorrelationId));
        model.addAttribute("sourceEventId", trim(sourceEventId));
        model.addAttribute("updatedFrom", trim(updatedFromRaw));
        model.addAttribute("updatedTo", trim(updatedToRaw));
        model.addAttribute("lookupSourceRecordId", trim(lookupSourceRecordId));
        model.addAttribute("lookupCanonicalId", trim(lookupCanonicalId));
        model.addAttribute("lookupByKey", sourceLinkOperationsFacade.findByKey(entityType, source, lookupSourceRecordId).orElse(null));
        model.addAttribute("lookupByCanonical", sourceLinkOperationsFacade.findByCanonical(entityType, lookupCanonicalId));
        return "admin/source-links";
    }

    @PostMapping("/reconcile")
    public String reconcileSourceLinks(
            @RequestParam(name = "confirmation", required = false) String confirmation,
            RedirectAttributes redirectAttributes
    ) {
        if (!"RECONCILE".equals(trim(confirmation))) {
            redirectAttributes.addFlashAttribute("errorMessage", "Source-link reconcile aborted. Type RECONCILE to proceed.");
            return "redirect:/admin/source-links";
        }
        var result = sourceLinkOperationsFacade.reconcileSourceLinks();
        redirectAttributes.addFlashAttribute("successMessage",
                "Source-link reconcile complete. updated=" + result.updated()
                        + ", skipped=" + result.skipped()
                        + ", errors=" + result.errors() + ".");
        return "redirect:/admin/source-links";
    }

    private Instant parseDateStart(String raw) {
        String value = trim(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Instant parseDateEnd(String raw) {
        String value = trim(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value).plusDays(1).atStartOfDay().minusNanos(1).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String trim(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }
}

