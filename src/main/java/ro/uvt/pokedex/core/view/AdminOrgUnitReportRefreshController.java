package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.service.application.OrgUnitReportRefreshService;

import java.util.List;

/**
 * Batch "Refresh all" actions for the org-unit report roll-ups. Kept separate from the view
 * controllers so they stay read-only (and their collaborator sets stay small).
 */
@Controller
@RequiredArgsConstructor
public class AdminOrgUnitReportRefreshController {

    private final OrgUnitReportRefreshService orgUnitReportRefreshService;

    @PostMapping("/admin/divisions/{divisionId}/reports/{reportId}/refresh-all")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String refreshDivision(@PathVariable String divisionId,
                                  @PathVariable String reportId,
                                  @RequestParam(defaultValue = "stale") String scope,
                                  @RequestParam(required = false) String label,
                                  Authentication authentication,
                                  RedirectAttributes flash) {
        runRefresh(OrgUnitReportRefreshEvent.UnitType.DIVISION, divisionId, reportId, scope, label,
                authentication, flash);
        return "redirect:/admin/divisions/" + divisionId + "/reports/" + reportId;
    }

    @PostMapping("/admin/departments/{departmentId}/reports/{reportId}/refresh-all")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String refreshDepartment(@PathVariable String departmentId,
                                    @PathVariable String reportId,
                                    @RequestParam(defaultValue = "stale") String scope,
                                    @RequestParam(required = false) String label,
                                    Authentication authentication,
                                    RedirectAttributes flash) {
        runRefresh(OrgUnitReportRefreshEvent.UnitType.DEPARTMENT, departmentId, reportId, scope, label,
                authentication, flash);
        return "redirect:/admin/departments/" + departmentId + "/reports/" + reportId;
    }

    @PostMapping("/admin/divisions/{divisionId}/reports/{reportId}/score-provisional")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String scoreProvisionalDivision(@PathVariable String divisionId,
                                           @PathVariable String reportId,
                                           @RequestParam(required = false) String label,
                                           Authentication authentication,
                                           RedirectAttributes flash) {
        runProvisional(OrgUnitReportRefreshEvent.UnitType.DIVISION, divisionId, reportId, label,
                authentication, flash);
        return "redirect:/admin/divisions/" + divisionId + "/reports/" + reportId;
    }

    @PostMapping("/admin/departments/{departmentId}/reports/{reportId}/score-provisional")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String scoreProvisionalDepartment(@PathVariable String departmentId,
                                             @PathVariable String reportId,
                                             @RequestParam(required = false) String label,
                                             Authentication authentication,
                                             RedirectAttributes flash) {
        runProvisional(OrgUnitReportRefreshEvent.UnitType.DEPARTMENT, departmentId, reportId, label,
                authentication, flash);
        return "redirect:/admin/departments/" + departmentId + "/reports/" + reportId;
    }

    private void runProvisional(OrgUnitReportRefreshEvent.UnitType unitType, String unitId, String reportId,
                                String label, Authentication authentication, RedirectAttributes flash) {
        try {
            OrgUnitReportRefreshService.ProvisionalScoreResult result =
                    orgUnitReportRefreshService.scoreProvisionalUnlinked(unitType, unitId, reportId, label,
                            authentication == null ? null : authentication.getName());
            flash.addFlashAttribute("successMessage", summarizeProvisional(result));
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
    }

    static String summarizeProvisional(OrgUnitReportRefreshService.ProvisionalScoreResult r) {
        StringBuilder message = new StringBuilder();
        message.append("Provisionally scored ").append(r.scored()).append(" of ").append(r.rosterSize())
                .append(" member(s) in ").append(Math.round(r.durationMs() / 1000.0)).append("s.");
        if (r.failed() > 0) {
            message.append(" ").append(r.failed()).append(" failed — see the server log.");
        }
        if (r.skippedConfirmed() > 0) {
            message.append(" ").append(r.skippedConfirmed())
                    .append(" with confirmed publications skipped (their own runs are authoritative).");
        }
        if (r.unresolved() > 0) {
            message.append(" ").append(r.unresolved()).append(" without linked identifiers");
            List<String> names = r.unresolvedNames();
            if (!names.isEmpty()) {
                message.append(": ").append(String.join(", ", names.subList(0, Math.min(5, names.size()))));
                if (names.size() > 5) {
                    message.append(" (+").append(names.size() - 5).append(" more)");
                }
            }
            message.append(" — link Scopus/WoS/Scholar/ORCID ids on their researcher profiles.");
        }
        return message.toString();
    }

    private void runRefresh(OrgUnitReportRefreshEvent.UnitType unitType, String unitId, String reportId,
                            String scope, String label, Authentication authentication, RedirectAttributes flash) {
        try {
            OrgUnitReportRefreshService.Scope resolvedScope = "all".equalsIgnoreCase(scope)
                    ? OrgUnitReportRefreshService.Scope.ALL
                    : OrgUnitReportRefreshService.Scope.STALE;
            OrgUnitReportRefreshService.RefreshAllResult result = orgUnitReportRefreshService.refreshAll(
                    unitType, unitId, reportId, resolvedScope, label,
                    authentication == null ? null : authentication.getName());
            flash.addFlashAttribute("successMessage", summarize(result));
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
    }

    private static String summarize(OrgUnitReportRefreshService.RefreshAllResult r) {
        StringBuilder message = new StringBuilder();
        message.append("Refreshed ").append(r.refreshed()).append(" of ").append(r.rosterSize())
                .append(" researcher(s) in ").append(Math.round(r.durationMs() / 1000.0)).append("s.");
        if (r.failed() > 0) {
            message.append(" ").append(r.failed()).append(" failed — see the server log.");
        }
        if (r.skippedProvisional() > 0) {
            message.append(" ").append(r.skippedProvisional())
                    .append(" provisional run(s) skipped — re-run those via the provisional report page.");
        }
        if (r.skippedFresh() > 0) {
            message.append(" ").append(r.skippedFresh()).append(" already fresh (skipped).");
        }
        return message.toString();
    }
}
