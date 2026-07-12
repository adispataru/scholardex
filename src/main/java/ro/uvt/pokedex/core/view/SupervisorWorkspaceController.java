package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.service.application.SupervisorCockpitService;

/**
 * The SUPERVISOR cockpit at {@code /supervisor}: a health strip and an attention-ranked list of the
 * units (divisions, departments, groups) where the authenticated principal is named as a head, for
 * one chosen report. Reachable by SUPERVISOR and PLATFORM_ADMIN; heads with no assignments see an
 * empty state.
 */
@Controller
@RequestMapping("/supervisor")
@RequiredArgsConstructor
public class SupervisorWorkspaceController {

    private final SupervisorCockpitService supervisorCockpitService;

    @GetMapping
    @PreAuthorize("hasAuthority('SUPERVISOR') or hasAuthority('PLATFORM_ADMIN')")
    public String workspace(@RequestParam(value = "report", required = false) String reportId,
                            Authentication authentication, Model model) {
        String userId = authentication == null ? null : authentication.getName();
        model.addAttribute("userId", userId);
        model.addAttribute("cockpit", supervisorCockpitService.buildView(userId, reportId));
        return "supervisor/workspace";
    }
}
