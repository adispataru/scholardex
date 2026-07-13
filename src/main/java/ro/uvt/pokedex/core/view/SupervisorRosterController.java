package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.DepartmentAffiliationService;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Roster self-service for department and division heads: add or remove existing researchers on a
 * department they head (or that sits under a division they head — see
 * {@link ro.uvt.pokedex.core.service.security.OrgUnitAccessService}). Lives under {@code /supervisor/**}
 * so the write access is scoped to a head's own units rather than widening admin.
 *
 * <p>Scope guardrails: the add pool is existing researchers only. Creating accounts, appointing heads,
 * creating/deleting units, and bulk staff import remain platform-admin operations.</p>
 */
@Controller
@RequestMapping("/supervisor/departments/{departmentId}/members")
@RequiredArgsConstructor
public class SupervisorRosterController {

    private final DepartmentAffiliationService departmentAffiliationService;
    private final DepartmentRepository departmentRepository;
    private final UserService userService;

    @GetMapping
    @PreAuthorize("@orgUnitAccess.canManageDepartment(#departmentId, authentication)")
    public String roster(@PathVariable String departmentId, Model model) {
        Department department = departmentRepository.findById(departmentId).orElse(null);
        if (department == null) {
            return "redirect:/supervisor";
        }

        Set<String> currentIds = new LinkedHashSet<>();
        departmentAffiliationService.listCurrentAffiliations(departmentId)
                .forEach(a -> currentIds.add(a.getUserId()));

        List<User> researchers = userService.findUsersWithResearcherProfile();
        List<User> members = researchers.stream()
                .filter(u -> currentIds.contains(u.getEmail()))
                .sorted(Comparator.comparing(SupervisorRosterController::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<User> candidates = researchers.stream()
                .filter(u -> !currentIds.contains(u.getEmail()))
                .sorted(Comparator.comparing(SupervisorRosterController::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        model.addAttribute("departmentId", departmentId);
        model.addAttribute("departmentName", department.getName());
        model.addAttribute("members", members);
        model.addAttribute("candidates", candidates);
        return "supervisor/department-roster";
    }

    @PostMapping("/add")
    @PreAuthorize("@orgUnitAccess.canManageDepartment(#departmentId, authentication)")
    public String addMember(@PathVariable String departmentId,
                            @RequestParam("userId") String userId,
                            RedirectAttributes redirectAttributes) {
        boolean added = departmentAffiliationService.addMember(departmentId, userId);
        redirectAttributes.addFlashAttribute(added ? "successMessage" : "infoMessage",
                added ? "Added " + userId + " to the department." : userId + " is already in this department.");
        return "redirect:/supervisor/departments/" + departmentId + "/members";
    }

    @PostMapping("/remove")
    @PreAuthorize("@orgUnitAccess.canManageDepartment(#departmentId, authentication)")
    public String removeMember(@PathVariable String departmentId,
                               @RequestParam("userId") String userId,
                               RedirectAttributes redirectAttributes) {
        boolean removed = departmentAffiliationService.removeMember(departmentId, userId);
        redirectAttributes.addFlashAttribute(removed ? "successMessage" : "infoMessage",
                removed ? "Removed " + userId + " from the department." : userId + " was not a current member.");
        return "redirect:/supervisor/departments/" + departmentId + "/members";
    }

    private static String displayName(User user) {
        if (user.getResearcherProfile() != null && user.getResearcherProfile().getName() != null
                && !user.getResearcherProfile().getName().isBlank()) {
            return user.getResearcherProfile().getName();
        }
        return user.getEmail() == null ? "" : user.getEmail();
    }
}
