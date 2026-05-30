package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ro.uvt.pokedex.core.service.application.SupervisorWorkspaceService;

/**
 * Per-user landing page for the SUPERVISOR role: surfaces only the divisions, departments,
 * and groups where the authenticated principal is named as a head/supervisor.
 *
 * <p>Path is reachable by SUPERVISOR and PLATFORM_ADMIN. SUPERVISOR users without any
 * head/supervisor assignments see an empty-state explaining how the workspace gets populated.
 */
@Controller
@RequestMapping("/supervisor")
@RequiredArgsConstructor
public class SupervisorWorkspaceController {

    private final SupervisorWorkspaceService supervisorWorkspaceService;

    @GetMapping
    @PreAuthorize("hasAuthority('SUPERVISOR') or hasAuthority('PLATFORM_ADMIN')")
    public String workspace(Authentication authentication, Model model) {
        String userId = authentication == null ? null : authentication.getName();
        SupervisorWorkspaceService.SupervisorWorkspaceView view =
                supervisorWorkspaceService.buildView(userId);
        model.addAttribute("userId", userId);
        model.addAttribute("divisions", view.divisions());
        model.addAttribute("departments", view.departments());
        model.addAttribute("groups", view.groups());
        model.addAttribute("institutionNames", view.institutionNames());
        model.addAttribute("divisionNames", view.divisionNames());
        model.addAttribute("departmentNames", view.departmentNames());
        model.addAttribute("isEmpty", view.isEmpty());
        return "supervisor/workspace";
    }
}
