package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.RegistryReviewService;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H142 slice 3, H144 — where the experts of a domain (heads of the departments that answer for it, and the experts an
 * admin names) rank the names researchers give and the registries do not know yet: artistic events, conferences,
 * organisations, awards, one tab each. Under {@code /user}: an expert needs no supervisor role; anyone who ranks
 * nothing is denied. Which names each expert may decide is the service's.
 */
@Controller
@RequestMapping("/user/registry/review")
@RequiredArgsConstructor
@PreAuthorize("@registryAccess.canReviewAny(authentication)")
public class RegistryReviewController {

    private static final String PAGE = "redirect:/user/registry/review?kind=";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Bucharest"));

    private final RegistryReviewService review;
    private final UserService userService;

    @GetMapping
    public String page(@RequestParam(name = "kind", required = false) String kind,
                       @RequestParam(name = "q", required = false) String q, Authentication authentication, Model model) {
        RegistryKind registry = kindOf(kind);
        RegistryReviewService.ReviewPage page = review.page(registry, authentication, q);
        Set<String> people = new LinkedHashSet<>();
        page.items().forEach(e -> { if (e.decidedBy() != null) people.add(e.decidedBy()); });
        page.decisions().forEach(d -> { if (d.by() != null) people.add(d.by()); });
        Map<String, String> when = new HashMap<>();
        page.decisions().forEach(d -> when.put(d.itemId() + "|" + d.at(), d.at() == null ? "" : WHEN.format(d.at())));
        page.items().forEach(e -> when.put(e.id(), e.decidedAt() == null ? "" : WHEN.format(e.decidedAt())));
        model.addAttribute("page", page);
        model.addAttribute("kind", registry);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("names", userService.findDisplayLabels(new ArrayList<>(people)));
        model.addAttribute("when", when);
        // the merge list, by domain (the ranked entries of the viewer's domains, sorted)
        Map<String, List<RegistryReviewService.MergeTarget>> mergeGroups = new LinkedHashMap<>();
        page.mergeTargets().forEach(t -> mergeGroups.computeIfAbsent(t.domain() == null ? "—" : t.domain(),
                d -> new ArrayList<>()).add(t));
        model.addAttribute("mergeGroups", mergeGroups);
        return "user/registry-review";
    }

    @PostMapping("/{kind}/rank")
    public String rank(@PathVariable("kind") String kind,
                       @RequestParam(name = "keys", required = false) List<String> keys,
                       @RequestParam(name = "level", required = false) String level,
                       @RequestParam(name = "category", required = false) String category,
                       @RequestParam(name = "criteria", required = false) List<String> criteria,
                       @RequestParam(name = "country", required = false) String country,
                       @RequestParam(name = "basis", required = false) String basis,
                       @RequestParam(name = "note", required = false) String note,
                       @RequestParam(name = "domain", required = false) String domain,
                       Authentication authentication, RedirectAttributes redirect) {
        RegistryKind registry = kindOf(kind);
        return flash(redirect, registry, review.rank(registry, keys,
                form(level, category, criteria, country, basis, note, domain), authentication), "#queue");
    }

    @PostMapping("/{kind}/merge")
    public String merge(@PathVariable("kind") String kind,
                        @RequestParam(name = "keys", required = false) List<String> keys,
                        @RequestParam(name = "target", required = false) String target,
                        Authentication authentication, RedirectAttributes redirect) {
        RegistryKind registry = kindOf(kind);
        return flash(redirect, registry, review.mergeAll(registry, keys, target, authentication), "#queue");
    }

    @PostMapping("/{kind}/reject")
    public String reject(@PathVariable("kind") String kind, @RequestParam("key") String key,
                         @RequestParam(name = "note", required = false) String note,
                         Authentication authentication, RedirectAttributes redirect) {
        RegistryKind registry = kindOf(kind);
        return flash(redirect, registry, review.reject(registry, key, note, authentication), "#queue");
    }

    @PostMapping("/{kind}/items/{id}/edit")
    public String edit(@PathVariable("kind") String kind, @PathVariable("id") String id,
                       @RequestParam(name = "level", required = false) String level,
                       @RequestParam(name = "category", required = false) String category,
                       @RequestParam(name = "criteria", required = false) List<String> criteria,
                       @RequestParam(name = "country", required = false) String country,
                       @RequestParam(name = "basis", required = false) String basis,
                       @RequestParam(name = "note", required = false) String note,
                       Authentication authentication, RedirectAttributes redirect) {
        RegistryKind registry = kindOf(kind);
        return flash(redirect, registry, review.edit(registry, id,
                form(level, category, criteria, country, basis, note, null), authentication), "#items");
    }

    @PostMapping("/{kind}/items/{id}/reopen")
    public String reopen(@PathVariable("kind") String kind, @PathVariable("id") String id,
                         Authentication authentication, RedirectAttributes redirect) {
        RegistryKind registry = kindOf(kind);
        return flash(redirect, registry, review.reopen(registry, id, authentication), "#decisions");
    }

    /** The registry named, else the artistic events (the first one). */
    private static RegistryKind kindOf(String kind) {
        if (kind == null || kind.isBlank()) {
            return RegistryKind.ARTISTIC_EVENT;
        }
        try {
            return RegistryKind.valueOf(kind.trim());
        } catch (IllegalArgumentException e) {
            return RegistryKind.ARTISTIC_EVENT;
        }
    }

    /** Blank fields are absent; the service checks every value against the registry's own lists. */
    private static RegistryReviewService.RankForm form(String level, String category, List<String> criteria, String country,
                                                       String basis, String note, String domain) {
        return new RegistryReviewService.RankForm(blankToNull(level), blankToNull(category),
                criteria == null ? List.of() : criteria.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).toList(),
                country, blankToNull(basis), note, domain);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The flash attributes carry message KEYS; the page resolves them in the reader's language. */
    private static String flash(RedirectAttributes redirect, RegistryKind kind, RegistryReviewService.Outcome outcome,
                                String anchor) {
        redirect.addFlashAttribute(outcome.done() ? "doneKey" : "refusedKey", outcome.messageKey());
        return PAGE + kind.name() + anchor;
    }
}
