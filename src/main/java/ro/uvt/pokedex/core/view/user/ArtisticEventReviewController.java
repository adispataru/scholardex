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
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.ArtisticEventReviewService;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H142 slice 3 — where the experts of a domain (heads of the departments that answer for it, and the experts an admin
 * names) rank the artistic events researchers name and the registry does not know yet. Under {@code /user}: an expert
 * needs no supervisor role; anyone who ranks nothing is denied. Which names each expert may decide is the service's.
 */
@Controller
@RequestMapping("/user/artistic-events/review")
@RequiredArgsConstructor
@PreAuthorize("@artisticEventAccess.canReviewAny(authentication)")
public class ArtisticEventReviewController {

    private static final String PAGE = "redirect:/user/artistic-events/review";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Bucharest"));

    private final ArtisticEventReviewService review;
    private final UserService userService;

    @GetMapping
    public String page(@RequestParam(name = "q", required = false) String q, Authentication authentication, Model model) {
        ArtisticEventReviewService.ReviewPage page = review.page(authentication, q);
        Set<String> people = new LinkedHashSet<>();
        page.events().forEach(e -> { if (e.decidedBy() != null) people.add(e.decidedBy()); });
        page.decisions().forEach(d -> { if (d.by() != null) people.add(d.by()); });
        Map<String, String> when = new HashMap<>();
        page.decisions().forEach(d -> when.put(d.eventId() + "|" + d.at(), d.at() == null ? "" : WHEN.format(d.at())));
        page.events().forEach(e -> when.put(e.id(), e.decidedAt() == null ? "" : WHEN.format(e.decidedAt())));
        model.addAttribute("page", page);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("names", userService.findDisplayLabels(new ArrayList<>(people)));
        model.addAttribute("when", when);
        model.addAttribute("ranks", ArtisticEvent.Rank.values());
        model.addAttribute("kinds", ArtisticEvent.Kind.values());
        model.addAttribute("bases", ArtisticEvent.Basis.values());
        // the merge list, by domain (the ranked events of the viewer's domains, sorted)
        Map<String, List<ArtisticEventReviewService.MergeTarget>> mergeGroups = new java.util.LinkedHashMap<>();
        page.mergeTargets().forEach(t -> mergeGroups.computeIfAbsent(t.domain() == null ? "—" : t.domain(),
                d -> new ArrayList<>()).add(t));
        model.addAttribute("mergeGroups", mergeGroups);
        return "user/artistic-events-review";
    }

    @PostMapping("/rank")
    public String rank(@RequestParam(name = "keys", required = false) List<String> keys,
                       @RequestParam(name = "rank", required = false) String rank,
                       @RequestParam(name = "kind", required = false) String kind,
                       @RequestParam(name = "country", required = false) String country,
                       @RequestParam(name = "basis", required = false) String basis,
                       @RequestParam(name = "note", required = false) String note,
                       @RequestParam(name = "domain", required = false) String domain,
                       Authentication authentication, RedirectAttributes redirect) {
        return flash(redirect, review.rank(keys, form(rank, kind, country, basis, note, domain), authentication), "#queue");
    }

    @PostMapping("/merge")
    public String merge(@RequestParam(name = "keys", required = false) List<String> keys,
                        @RequestParam(name = "target", required = false) String target,
                        Authentication authentication, RedirectAttributes redirect) {
        return flash(redirect, review.mergeAll(keys, target, authentication), "#queue");
    }

    @PostMapping("/reject")
    public String reject(@RequestParam("key") String key, @RequestParam(name = "note", required = false) String note,
                         Authentication authentication, RedirectAttributes redirect) {
        return flash(redirect, review.reject(key, note, authentication), "#queue");
    }

    @PostMapping("/events/{id}/edit")
    public String edit(@PathVariable String id,
                       @RequestParam(name = "rank", required = false) String rank,
                       @RequestParam(name = "kind", required = false) String kind,
                       @RequestParam(name = "country", required = false) String country,
                       @RequestParam(name = "basis", required = false) String basis,
                       @RequestParam(name = "note", required = false) String note,
                       Authentication authentication, RedirectAttributes redirect) {
        return flash(redirect, review.edit(id, form(rank, kind, country, basis, note, null), authentication), "#events");
    }

    @PostMapping("/events/{id}/reopen")
    public String reopen(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
        return flash(redirect, review.reopen(id, authentication), "#decisions");
    }

    private static ArtisticEventReviewService.RankForm form(String rank, String kind, String country, String basis,
                                                            String note, String domain) {
        return new ArtisticEventReviewService.RankForm(parse(ArtisticEvent.Rank.class, rank),
                parse(ArtisticEvent.Kind.class, kind), country, parse(ArtisticEvent.Basis.class, basis) == null ? null : basis,
                note, domain);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The flash attributes carry message KEYS; the page resolves them in the reader's language. */
    private static String flash(RedirectAttributes redirect, ArtisticEventReviewService.Outcome outcome, String anchor) {
        redirect.addFlashAttribute(outcome.done() ? "doneKey" : "refusedKey", outcome.messageKey());
        return PAGE + anchor;
    }
}
