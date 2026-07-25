package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ro.uvt.pokedex.core.service.application.ChangelogService;

/**
 * H86 — the "Noutăți / What's new" page. Answers the question a fast-moving scoring platform keeps raising:
 * "why did my score change?" Researchers see researcher-facing + shared entries; platform admins additionally
 * see operational ones.
 */
@Controller
@RequestMapping("/changelog")
@RequiredArgsConstructor
public class ChangelogViewController {

    private final ChangelogService changelogService;

    @GetMapping
    public String showChangelog(Authentication authentication, Model model) {
        boolean isAdmin = hasAuthority(authentication, "PLATFORM_ADMIN");
        model.addAttribute("entriesByDate", changelogService.groupedByDate(isAdmin));
        model.addAttribute("entryCount", changelogService.entriesFor(isAdmin).size());
        model.addAttribute("viewerIsAdmin", isAdmin);
        return "changelog";
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
