package ro.uvt.pokedex.core.view;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;
import ro.uvt.pokedex.core.service.application.RequestYearRangeSupport;
import ro.uvt.pokedex.core.service.application.model.PublicationMetadataPatch;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorWorkbookExportViewModel;
import ro.uvt.pokedex.core.service.application.model.UserPublicationCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserScopusTasksViewModel;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportResult;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportStatus;

import java.io.IOException;
import java.util.*;

@Controller
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserViewController {

    private final UserService userService;
    // H02 V01 debt: remaining Z1->Z4 dependencies for deferred endpoints.
    private final UserPublicationFacade userPublicationFacade;
    private final UserScopusTaskFacade userScopusTaskFacade;
    private final UserReportFacade userReportFacade;
    private final UserIndividualReportRunService userIndividualReportRunService;

    @GetMapping()
    public String showDashboardCompatibilityRedirect() {
        return "redirect:/user/workspace";
    }

    @GetMapping("/dashboard")
    public String showDashboard() {
        return "redirect:/user/workspace";
    }

    @GetMapping("/profile")
    public String showProfilePage() {
        return "redirect:/user/workspace#profile";
    }

    @GetMapping("/publications")
    public String showPublicationsPage() {
        return "redirect:/user/workspace#publications";
    }

    @GetMapping("/authors/view/{id}")
    public String showAuthorPublicationsPage(@PathVariable("id") String authorId, Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }

        Optional<UserPublicationsViewModel> viewModelOpt = userPublicationFacade.buildAuthorPublicationsView(authorId);
        if (viewModelOpt.isEmpty()) {
            return "redirect:/user/publications";
        }
        UserPublicationsViewModel viewModel = viewModelOpt.get();

        model.addAttribute("publications", viewModel.publications());
        model.addAttribute("hIndex", viewModel.hIndex());
        model.addAttribute("hIndices", viewModel.hIndices()); // H67: source-attributed h breakdown
        model.addAttribute("authorMap", viewModel.authorMap());
        model.addAttribute("forumMap", viewModel.forumMap());
        model.addAttribute("numCitations", viewModel.numCitations());
        model.addAttribute("profileAuthor", viewModel.profileAuthor());
        model.addAttribute("affiliations", viewModel.affiliations());
        model.addAttribute("publicationPageTitle", "Author Publications");
        model.addAttribute("publicationTableTitle", "Author Publications");
        model.addAttribute("publicationPageSubtitle", viewModel.authorMap().get(authorId) != null
                ? viewModel.authorMap().get(authorId).getName()
                : authorId);
        model.addAttribute("showPublicationActions", false);
        model.addAttribute("user", currentUser);
        return "user/publications";
    }

    @GetMapping("/publications/scopus-tasks")
    public String showScopusTasksPage() {
        return "redirect:/user/workspace#profile";
    }

    @PostMapping("/tasks/scopus/update-publications")
    public ResponseEntity<ScopusPublicationUpdate> createScopusUpdateTask(@ModelAttribute ScopusPublicationUpdate task,
                                                 Authentication authentication,
                                                 RedirectAttributes redirectAttributes) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        ScopusPublicationUpdate created = userScopusTaskFacade.createPublicationTask(currentUser.getEmail(), task);
        redirectAttributes.addFlashAttribute("successMessage", "Scopus update task created.");
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PostMapping("/tasks/scopus/update-citations")
    public ResponseEntity<ScopusCitationsUpdate> createScopusCitationsUpdateTask(@ModelAttribute ScopusCitationsUpdate task,
                                                                          Authentication authentication,
                                                                          RedirectAttributes redirectAttributes) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        ScopusCitationsUpdate created = userScopusTaskFacade.createCitationTask(currentUser.getEmail(), task);
        redirectAttributes.addFlashAttribute("successMessage", "Scopus update task created.");
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/publications/citations")
    public String showPublicationCitationsPage() {
        return "redirect:/user/workspace#publications";
    }

    @GetMapping("/publications/edit/{eid}")
    public String showEditPublicationForm() {
        return "redirect:/user/workspace#publications";
    }

    @GetMapping("indicators/export/{id}")
    @ResponseBody
    public void exportIndicatorResults(@PathVariable String id, Authentication authentication, HttpServletResponse response) throws IOException {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            response.sendRedirect("/login");
            return;
        }

        Optional<UserIndicatorWorkbookExportViewModel> workbook = userReportFacade.buildIndicatorWorkbookExport(currentUser.getEmail(), id);
        if (workbook.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        UserIndicatorWorkbookExportViewModel vm = workbook.get();
        response.setContentType(vm.contentType());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + vm.fileName() + "\"");
        response.getOutputStream().write(vm.workbookBytes());
    }

    @GetMapping("/individual-reports")
    public String showIndividualReportsList() {
        return "redirect:/user/evaluation";
    }

    @GetMapping("/indicators/apply/{id}")
    public String showIndicatorApplyPage(@PathVariable("id") String id) {
        return "redirect:/user/evaluation#indicator-" + id;
    }

    @PostMapping("/indicators/apply/{id}/refresh")
    public String refreshIndicatorApply(@PathVariable("id") String id) {
        return "redirect:/user/evaluation#indicator-" + id;
    }

    @GetMapping("/individual-reports/view/{id}")
    public String showIndividualReportView(@PathVariable("id") String id) {
        return "redirect:/user/evaluation?report=" + id;
    }

    @PostMapping("/individual-reports/view/{id}/refresh-all-indicators")
    public String refreshAllIndicators(@PathVariable("id") String id, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }
        userIndividualReportRunService.refreshRunWithAllIndicators(currentUser.getEmail(), id);
        return "redirect:/user/evaluation?report=" + id;
    }

    // File: src/main/java/ro/uvt/pokedex/core/view/UserViewController.java
    @GetMapping("/exports/cnfis")
    @ResponseBody
    public void createCNFISReport2025(Authentication authentication,
                                      HttpServletResponse response,
                                      @RequestParam(name = "start", defaultValue = "2021") String startYear,
                                      @RequestParam(name = "end", defaultValue = "2024") String endYear) throws IOException {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            response.sendRedirect("/login");
            return;
        }
        RequestYearRangeSupport.YearRange yearRange;
        try {
            yearRange = RequestYearRangeSupport.parseAndValidate(startYear, endYear);
        } catch (IllegalArgumentException ex) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
            return;
        }

        int start = yearRange.start();
        int end = yearRange.end();
        UserWorkbookExportResult exportResult = userReportFacade.buildUserCnfisWorkbookExport(currentUser.getEmail(), start, end);
        if (exportResult.status() == UserWorkbookExportStatus.UNAUTHORIZED) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (exportResult.status() == UserWorkbookExportStatus.NOT_FOUND) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        response.setContentType(exportResult.contentType());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + exportResult.fileName() + "\"");
        response.getOutputStream().write(exportResult.workbookBytes());
    }

}
