package ro.uvt.pokedex.core.view.user;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.CnfisReportingFacade;
import ro.uvt.pokedex.core.service.application.model.CnfisSheetViewModel;
import ro.uvt.pokedex.core.service.application.model.CnfisEditionViewModel;

import java.io.IOException;
import java.util.Optional;

/**
 * H129 — the CNFIS entry of the sidebar: the editions of the reporting and, for one of them, the person's
 * Anexa 5 as the platform fills it, the head of the sheet the person fills in, the frozen copies, the
 * downloads. Everything a person sees or changes here is their own.
 */
@Controller
@RequestMapping("/user/cnfis")
@RequiredArgsConstructor
public class CnfisWorkspaceController {

    private final CnfisReportingFacade cnfisReportingFacade;

    @GetMapping
    public String showCnfis(@RequestParam(name = "edition", required = false) Integer reportingYear,
                            Model model, Authentication authentication) {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            return "redirect:/login";
        }
        model.addAttribute("user", currentUser);
        model.addAttribute("editions", cnfisReportingFacade.editions());
        Optional<CnfisEditionViewModel> edition = cnfisReportingFacade.edition(reportingYear);
        if (edition.isEmpty()) {
            return "redirect:/user/cnfis";
        }
        model.addAttribute("edition", edition.get().reportingYear());
        Optional<CnfisSheetViewModel> sheet = cnfisReportingFacade.buildSheet(currentUser.getEmail(), edition.get().reportingYear());
        if (sheet.isEmpty()) {
            model.addAttribute("noProfile", true);
        } else {
            model.addAttribute("sheet", sheet.get());
        }
        return "user/cnfis";
    }

    @PostMapping("/{edition}/header")
    public String saveHeader(@PathVariable("edition") int reportingYear,
                             @RequestParam(required = false) String domainCode,
                             @RequestParam(required = false) String scoreReportId,
                             @RequestParam(required = false) Double scoreTyped,
                             @RequestParam(required = false) String unmetCriterion,
                             @RequestParam(required = false) Integer hirschGoogleScholar,
                             @RequestParam(required = false) Integer hirschWebOfScience,
                             @RequestParam(required = false) Integer hirschScopus,
                             Authentication authentication, RedirectAttributes redirect) {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            return "redirect:/login";
        }
        cnfisReportingFacade.saveHeader(currentUser.getEmail(), reportingYear, new CnfisReportingFacade.HeaderForm(
                        domainCode, scoreReportId, scoreTyped, unmetCriterion, hirschGoogleScholar, hirschWebOfScience, hirschScopus))
                .ifPresent(saved -> redirect.addFlashAttribute("successMessage", "cnfis.header.saved"));
        return "redirect:/user/cnfis?edition=" + reportingYear;
    }

    @PostMapping("/{edition}/freeze")
    public String freeze(@PathVariable("edition") int reportingYear, Authentication authentication, RedirectAttributes redirect) {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            return "redirect:/login";
        }
        cnfisReportingFacade.freeze(currentUser.getEmail(), reportingYear)
                .ifPresent(frozen -> redirect.addFlashAttribute("successMessage", "cnfis.frozen"));
        return "redirect:/user/cnfis?edition=" + reportingYear;
    }

    @PostMapping("/snapshots/{id}/release")
    public String release(@PathVariable("id") String snapshotId, @RequestParam("edition") int reportingYear,
                          Authentication authentication, RedirectAttributes redirect) {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            return "redirect:/login";
        }
        CnfisReportingFacade.ReleaseResult result = cnfisReportingFacade.release(currentUser.getEmail(), snapshotId);
        redirect.addFlashAttribute(result == CnfisReportingFacade.ReleaseResult.RELEASED ? "successMessage" : "errorMessage",
                switch (result) {
                    case RELEASED -> "cnfis.released";
                    case LOCKED -> "cnfis.release.locked";
                    case NOT_FOUND -> "cnfis.release.notFound";
                });
        return "redirect:/user/cnfis?edition=" + reportingYear;
    }

    @GetMapping("/{edition}/export")
    public void exportLive(@PathVariable("edition") int reportingYear, Authentication authentication,
                           HttpServletResponse response) throws IOException {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            response.sendRedirect("/login");
            return;
        }
        Optional<byte[]> bytes = cnfisReportingFacade.exportLive(currentUser.getEmail(), reportingYear);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, bytes.get(), "Anexa5_CNFIS_" + reportingYear + ".xlsx");
    }

    @GetMapping("/{edition}/export-arts")
    public void exportArtsLive(@PathVariable("edition") int reportingYear, Authentication authentication,
                               HttpServletResponse response) throws IOException {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            response.sendRedirect("/login");
            return;
        }
        Optional<byte[]> bytes = cnfisReportingFacade.exportArtsLive(currentUser.getEmail(), reportingYear);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, bytes.get(), "Anexa5.1_CNFIS_" + reportingYear + ".xlsx");
    }

    @GetMapping("/{edition}/export-sport")
    public void exportSportLive(@PathVariable("edition") int reportingYear, Authentication authentication,
                                HttpServletResponse response) throws IOException {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            response.sendRedirect("/login");
            return;
        }
        Optional<byte[]> bytes = cnfisReportingFacade.exportSportLive(currentUser.getEmail(), reportingYear);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, bytes.get(), "Anexa5.2_CNFIS_" + reportingYear + ".xlsx");
    }

    @GetMapping("/snapshots/{id}/export-sport")
    public void exportSportSnapshot(@PathVariable("id") String snapshotId, Authentication authentication,
                                    HttpServletResponse response) throws IOException {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            response.sendRedirect("/login");
            return;
        }
        Optional<byte[]> bytes = cnfisReportingFacade.exportSportSnapshot(currentUser.getEmail(), snapshotId);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, bytes.get(), "Anexa5.2_CNFIS_inghetata.xlsx");
    }

    @GetMapping("/snapshots/{id}/export-arts")
    public void exportArtsSnapshot(@PathVariable("id") String snapshotId, Authentication authentication,
                                   HttpServletResponse response) throws IOException {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            response.sendRedirect("/login");
            return;
        }
        Optional<byte[]> bytes = cnfisReportingFacade.exportArtsSnapshot(currentUser.getEmail(), snapshotId);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, bytes.get(), "Anexa5.1_CNFIS_inghetata.xlsx");
    }

    @GetMapping("/{edition}/export-humanities")
    public void exportHumanitiesLive(@PathVariable("edition") int reportingYear, Authentication authentication,
                                     HttpServletResponse response) throws IOException {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            response.sendRedirect("/login");
            return;
        }
        Optional<byte[]> bytes = cnfisReportingFacade.exportHumanitiesLive(currentUser.getEmail(), reportingYear);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, bytes.get(), "Anexa5.3_CNFIS_" + reportingYear + ".xlsx");
    }

    @GetMapping("/snapshots/{id}/export-humanities")
    public void exportHumanitiesSnapshot(@PathVariable("id") String snapshotId, Authentication authentication,
                                         HttpServletResponse response) throws IOException {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            response.sendRedirect("/login");
            return;
        }
        Optional<byte[]> bytes = cnfisReportingFacade.exportHumanitiesSnapshot(currentUser.getEmail(), snapshotId);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, bytes.get(), "Anexa5.3_CNFIS_inghetata.xlsx");
    }

    @GetMapping("/snapshots/{id}/export")
    public void exportSnapshot(@PathVariable("id") String snapshotId, Authentication authentication,
                               HttpServletResponse response) throws IOException {
        User currentUser = signedIn(authentication);
        if (currentUser == null) {
            response.sendRedirect("/login");
            return;
        }
        Optional<byte[]> bytes = cnfisReportingFacade.exportSnapshot(currentUser.getEmail(), snapshotId);
        if (bytes.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, bytes.get(), "Anexa5_CNFIS_inghetata.xlsx");
    }

    private static void write(HttpServletResponse response, byte[] bytes, String filename) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getOutputStream().write(bytes);
    }

    private static User signedIn(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }
}
