package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.service.application.DivisionReportFacade;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;

import java.time.Instant;
import java.util.Optional;

/**
 * Surfaces individual-report roll-ups for a single OrgDivision (Faculty / Institute / Service).
 * Aggregates researchers from every department under the division; per-researcher scoring
 * is shared with the Department view.
 */
@Controller
@RequestMapping("/admin/divisions/{divisionId}/reports")
@RequiredArgsConstructor
public class AdminDivisionReportsController {

    private final DivisionReportFacade divisionReportFacade;

    @GetMapping
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String listReports(@PathVariable String divisionId, Model model) {
        var division = divisionReportFacade.findDivision(divisionId);
        if (division.isEmpty()) return "redirect:/admin/divisions";
        model.addAttribute("unitType", "division");
        model.addAttribute("unitName", division.get().getName());
        model.addAttribute("unitId", divisionId);
        model.addAttribute("backHref", "/admin/divisions");
        model.addAttribute("reports", divisionReportFacade.listReportsVisibleForDivision(divisionId));
        model.addAttribute("reportLinkPrefix", "/admin/divisions/" + divisionId + "/reports/");
        return "admin/orgunit-reports-list";
    }

    @GetMapping("/{reportId}")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String viewReport(@PathVariable String divisionId,
                             @PathVariable String reportId,
                             @RequestParam(value = "compareTo", required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant compareTo,
                             Model model) {
        Optional<OrgUnitReportViewModel> view = divisionReportFacade.buildView(divisionId, reportId, compareTo);
        if (view.isEmpty()) return "redirect:/admin/divisions/" + divisionId + "/reports";
        OrgUnitReportViewModel vm = view.get();
        model.addAttribute("unitType", "division");
        model.addAttribute("vm", vm);
        model.addAttribute("backHref", "/admin/divisions/" + divisionId + "/reports");
        model.addAttribute("promotionsHref",
                "/admin/divisions/" + divisionId + "/reports/" + reportId + "/promotions");
        return "admin/orgunit-report-view";
    }

    @GetMapping("/{reportId}/promotions")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN') or hasAuthority('SUPERVISOR')")
    public String promotionBoard(@PathVariable String divisionId,
                                 @PathVariable String reportId,
                                 @RequestParam(name = "exclude", required = false) String exclude,
                                 Model model) {
        java.util.Set<Integer> excluded = parseExcludedCriteria(exclude);
        Optional<DivisionReportFacade.PromotionBoardView> view =
                divisionReportFacade.buildPromotionBoard(divisionId, reportId, excluded);
        if (view.isEmpty()) return "redirect:/admin/divisions/" + divisionId + "/reports";
        String baseHref = "/admin/divisions/" + divisionId + "/reports/" + reportId + "/promotions";
        // Only indices the report actually defines are toggleable; stray URL values are dropped.
        int criteriaCount = view.get().report().getCriteria() == null
                ? 0 : view.get().report().getCriteria().size();
        excluded.removeIf(i -> i < 0 || i >= criteriaCount);
        java.util.List<CriterionToggle> toggles = new java.util.ArrayList<>();
        for (int i = 0; i < criteriaCount; i++) {
            String name = view.get().report().getCriteria().get(i).getName();
            toggles.add(new CriterionToggle(
                    i,
                    name == null || name.isBlank() ? "C" + (i + 1) : name,
                    excluded.contains(i),
                    toggleHref(baseHref, excluded, i)));
        }
        model.addAttribute("vm", view.get());
        model.addAttribute("criterionToggles", toggles);
        model.addAttribute("excludedCount", excluded.size());
        model.addAttribute("resetHref", baseHref);
        model.addAttribute("backHref", "/admin/divisions/" + divisionId + "/reports/" + reportId);
        return "admin/orgunit-promotions";
    }

    /** One chip on the promotions board: click toggles the criterion in/out of the computed buckets. */
    public record CriterionToggle(int index, String name, boolean excluded, String toggleHref) {}

    private static java.util.Set<Integer> parseExcludedCriteria(String exclude) {
        java.util.Set<Integer> excluded = new java.util.TreeSet<>();
        if (exclude == null || exclude.isBlank()) {
            return excluded;
        }
        for (String part : exclude.split(",")) {
            try {
                excluded.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // stray token in a hand-edited URL — skip it
            }
        }
        return excluded;
    }

    private static String toggleHref(String baseHref, java.util.Set<Integer> excluded, int index) {
        java.util.Set<Integer> toggled = new java.util.TreeSet<>(excluded);
        if (!toggled.remove(index)) {
            toggled.add(index);
        }
        if (toggled.isEmpty()) {
            return baseHref;
        }
        StringBuilder sb = new StringBuilder();
        for (Integer i : toggled) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        return baseHref + "?exclude=" + sb;
    }
}
