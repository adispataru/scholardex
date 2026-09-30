package ro.uvt.pokedex.core.view;

import jakarta.servlet.http.HttpServletResponse;
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
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisUnitSheet;
import ro.uvt.pokedex.core.service.application.CnfisReportingFacade;
import ro.uvt.pokedex.core.service.application.CnfisUnitFacade;
import ro.uvt.pokedex.core.service.application.model.CnfisUnitViewModel;

import java.io.IOException;
import java.util.Optional;

/**
 * H129 slice 3 — the CNFIS reporting of a unit, for its head: who froze a sheet, a provisional sheet for
 * whoever did not, Anexa 6 built from the sheets. A department's page is for the department's heads (and
 * the faculty's, as for the roster); a faculty's page for the faculty's heads. The unit kind is in the URL,
 * so the access rule of each kind applies to every action of that kind.
 */
@Controller
@RequestMapping("/supervisor/{kind:departments|divisions}/{unitId}/cnfis")
@RequiredArgsConstructor
public class CnfisUnitController {

    private final CnfisUnitFacade cnfisUnitFacade;
    private final CnfisReportingFacade cnfisReportingFacade;

    @GetMapping
    @PreAuthorize("(#kind == 'departments' and @orgUnitAccess.canManageDepartment(#unitId, authentication))"
            + " or (#kind == 'divisions' and @orgUnitAccess.canManageDivision(#unitId, authentication))")
    public String show(@PathVariable String kind, @PathVariable String unitId,
                       @RequestParam(name = "edition", required = false) Integer reportingYear,
                       Model model) {
        Optional<Integer> year = cnfisReportingFacade.edition(reportingYear).map(e -> e.reportingYear());
        if (year.isEmpty()) {
            return "redirect:/supervisor";
        }
        Optional<CnfisUnitViewModel> unit = cnfisUnitFacade.buildUnit(unitKind(kind), unitId, year.get());
        if (unit.isEmpty()) {
            return "redirect:/supervisor";
        }
        model.addAttribute("unit", unit.get());
        model.addAttribute("kind", kind);
        model.addAttribute("editions", cnfisReportingFacade.editions());
        return "supervisor/cnfis-unit";
    }

    @PostMapping("/{edition}/provisional")
    @PreAuthorize("(#kind == 'departments' and @orgUnitAccess.canManageDepartment(#unitId, authentication))"
            + " or (#kind == 'divisions' and @orgUnitAccess.canManageDivision(#unitId, authentication))")
    public String provisional(@PathVariable String kind, @PathVariable String unitId,
                              @PathVariable("edition") int reportingYear,
                              @RequestParam(name = "member", required = false) String memberEmail,
                              Authentication authentication, RedirectAttributes redirect) {
        int generated = cnfisUnitFacade.generateProvisional(unitKind(kind), unitId, reportingYear,
                blankToNull(memberEmail), authentication.getName());
        redirect.addFlashAttribute("successMessage", "cnfis.unit.provisional.generated");
        redirect.addFlashAttribute("successCount", generated);
        return back(kind, unitId, reportingYear);
    }

    @PostMapping("/{edition}/build")
    @PreAuthorize("(#kind == 'departments' and @orgUnitAccess.canManageDepartment(#unitId, authentication))"
            + " or (#kind == 'divisions' and @orgUnitAccess.canManageDivision(#unitId, authentication))")
    public String build(@PathVariable String kind, @PathVariable String unitId, @PathVariable("edition") int reportingYear,
                        Authentication authentication, RedirectAttributes redirect) {
        cnfisUnitFacade.buildTable(unitKind(kind), unitId, reportingYear, authentication.getName())
                .ifPresent(t -> redirect.addFlashAttribute("successMessage", "cnfis.unit.built"));
        return back(kind, unitId, reportingYear);
    }

    @PostMapping("/tables/{tableId}/delete")
    @PreAuthorize("(#kind == 'departments' and @orgUnitAccess.canManageDepartment(#unitId, authentication))"
            + " or (#kind == 'divisions' and @orgUnitAccess.canManageDivision(#unitId, authentication))")
    public String delete(@PathVariable String kind, @PathVariable String unitId, @PathVariable String tableId,
                         @RequestParam("edition") int reportingYear, RedirectAttributes redirect) {
        if (cnfisUnitFacade.deleteTable(unitKind(kind), unitId, tableId)) {
            redirect.addFlashAttribute("successMessage", "cnfis.unit.deleted");
        }
        return back(kind, unitId, reportingYear);
    }

    @GetMapping("/tables/{tableId}/export")
    @PreAuthorize("(#kind == 'departments' and @orgUnitAccess.canManageDepartment(#unitId, authentication))"
            + " or (#kind == 'divisions' and @orgUnitAccess.canManageDivision(#unitId, authentication))")
    public void export(@PathVariable String kind, @PathVariable String unitId, @PathVariable String tableId,
                       HttpServletResponse response) throws IOException {
        Optional<byte[]> bytes = cnfisUnitFacade.exportTable(unitKind(kind), unitId, tableId);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"Anexa6_CNFIS.xlsx\"");
        response.getOutputStream().write(bytes.get());
    }

    @GetMapping("/tables/{tableId}/export-arts")
    @PreAuthorize("(#kind == 'departments' and @orgUnitAccess.canManageDepartment(#unitId, authentication))"
            + " or (#kind == 'divisions' and @orgUnitAccess.canManageDivision(#unitId, authentication))")
    public void exportArts(@PathVariable String kind, @PathVariable String unitId, @PathVariable String tableId,
                           HttpServletResponse response) throws IOException {
        Optional<byte[]> bytes = cnfisUnitFacade.exportArtsTable(unitKind(kind), unitId, tableId);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"Anexa6.1_CNFIS.xlsx\"");
        response.getOutputStream().write(bytes.get());
    }

    @GetMapping("/tables/{tableId}/export-humanities")
    @PreAuthorize("(#kind == 'departments' and @orgUnitAccess.canManageDepartment(#unitId, authentication))"
            + " or (#kind == 'divisions' and @orgUnitAccess.canManageDivision(#unitId, authentication))")
    public void exportHumanities(@PathVariable String kind, @PathVariable String unitId, @PathVariable String tableId,
                                 HttpServletResponse response) throws IOException {
        Optional<byte[]> bytes = cnfisUnitFacade.exportHumanitiesTable(unitKind(kind), unitId, tableId);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"Anexa6.3_CNFIS.xlsx\"");
        response.getOutputStream().write(bytes.get());
    }

    private static CnfisUnitSheet.UnitKind unitKind(String kind) {
        return "divisions".equals(kind) ? CnfisUnitSheet.UnitKind.DIVISION : CnfisUnitSheet.UnitKind.DEPARTMENT;
    }

    private static String back(String kind, String unitId, int reportingYear) {
        return "redirect:/supervisor/" + kind + "/" + unitId + "/cnfis?edition=" + reportingYear;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
