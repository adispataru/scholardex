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
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;
import ro.uvt.pokedex.core.service.application.RequestYearRangeSupport;
import ro.uvt.pokedex.core.service.application.UserRankingFacade;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorWorkbookExportViewModel;
import ro.uvt.pokedex.core.service.application.model.UserPublicationCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;
import ro.uvt.pokedex.core.service.application.model.UserScopusTasksViewModel;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportResult;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportStatus;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserViewController {

    private final UserService userService;
    private final ResearcherService researcherService;
    // H02 V01 debt: remaining Z1->Z4 dependencies for deferred endpoints.
    private final UserPublicationFacade userPublicationFacade;
    private final UserScopusTaskFacade userScopusTaskFacade;
    private final UserReportFacade userReportFacade;
    private final UserRankingFacade userRankingFacade;
    private final UserIndicatorResultService userIndicatorResultService;
    private final UserIndividualReportRunService userIndividualReportRunService;


    @GetMapping()
    public String showDashboard(Model model) {

        return "user/dashboard"; // Returns the users.html template
    }

    @GetMapping("/profile")
    public String showProfilePage(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        String researcherId = currentUser.getResearcherId();
        if (researcherId == null){
            model.addAttribute("researchProfile", null);
        }else{
            Optional<Researcher> researcherById = researcherService.findResearcherById(researcherId);
            researcherById.ifPresent(researcher -> model.addAttribute("researchProfile", researcher));
        }
        model.addAttribute("newProfile", new Researcher());
        return "user/profile";
    }

    @GetMapping("/publications")
    public String showPublicationsPage(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        String researcherId = currentUser.getResearcherId();
        Optional<UserPublicationsViewModel> viewModel = userPublicationFacade.buildUserPublicationsView(researcherId);
        viewModel.ifPresent(vm -> {
            model.addAttribute("publications", vm.publications());
            model.addAttribute("hIndex", vm.hIndex());
            model.addAttribute("authorMap", vm.authorMap());
            model.addAttribute("forumMap", vm.forumMap());
            model.addAttribute("numCitations", vm.numCitations());
        });

        model.addAttribute("user", currentUser);
        return "user/publications";
    }

    @GetMapping("/publications/scopus_tasks")
    public String showScopusTasksPage(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }

        UserScopusTasksViewModel viewModel = userScopusTaskFacade.buildTasksView(currentUser.getEmail(), currentUser.getResearcherId());
        model.addAttribute("researcher", viewModel.researcher());
        model.addAttribute("tasks", viewModel.tasks());
        model.addAttribute("citationsTasks", viewModel.citationsTasks());
        model.addAttribute("user", currentUser);
        return "user/tasks";
    }


    @PostMapping("/tasks/scopus/update")
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

    @PostMapping("/tasks/scopus/updateCitations")
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
    public String showPublicationCitationsPage(Model model, Authentication authentication, @RequestParam("id") String publicationId) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        Optional<UserPublicationCitationsViewModel> viewModel = userPublicationFacade.buildCitationsView(publicationId);
        viewModel.ifPresent(vm -> {
            model.addAttribute("publication", vm.publication());
            model.addAttribute("citations", vm.citations());
            model.addAttribute("forum", vm.forum());
            model.addAttribute("authorMapping", vm.authorMapping());
            model.addAttribute("forumMap", vm.forumMap());
        });

        model.addAttribute("user", currentUser);
        return "user/citations";
    }


    @GetMapping("/publications/edit/{eid}")
    public String showEditPublicationForm(@PathVariable("eid") String publicationId, Model model) {
        Optional<Publication> publicationOpt = userPublicationFacade.findPublicationForEdit(publicationId);
        if (publicationOpt.isPresent()) {
            model.addAttribute("publication", publicationOpt.get());
            return "user/publications-edit";
        } else {
            return "redirect:/user/publications"; // or an error page
        }
    }

    @PostMapping("/publications/save/{eid}")
    public String savePublication(@ModelAttribute Publication publication, RedirectAttributes redirectAttributes, @PathVariable("eid") String publicationId) {
        userPublicationFacade.updatePublicationMetadata(publicationId, publication);
        redirectAttributes.addFlashAttribute("successMessage", "Publication updated successfully.");
        return "redirect:/user/publications";
    }


    @GetMapping("/indicators")
    public String showPubCriteriaPage(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        UserIndicatorsViewModel viewModel = userReportFacade.buildIndicatorsView(currentUser.getEmail());
        model.addAttribute("indicators", viewModel.indicators());
        //adjust number of authors shown on page.

        model.addAttribute("user", currentUser);
        return "user/indicators";
    }


    @GetMapping("/indicators/apply/{id}")
    public String showCriteriaResultsPage(Model model, Authentication authentication, @PathVariable("id") String id) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        IndicatorApplyResultDto result = userIndicatorResultService.getOrCreateLatest(currentUser.getEmail(), id);
        result.rawGraph().forEach(model::addAttribute);
        model.addAttribute("resultMetaSource", result.source());
        model.addAttribute("resultMetaUpdatedAt", result.updatedAt());
        model.addAttribute("resultMetaRefreshVersion", result.refreshVersion());
        model.addAttribute("user", currentUser);
        return result.viewName();
    }

    @PostMapping("/indicators/apply/{id}/refresh")
    public String refreshCriteriaResultsPage(Authentication authentication, @PathVariable("id") String id) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }
        userIndicatorResultService.refreshLatest(currentUser.getEmail(), id);
        return "redirect:/user/indicators/apply/" + id;
    }

    @GetMapping("indicators/export/{id}")
    @ResponseBody
    public void exportIndicatorResults(@PathVariable("id") String id, Authentication authentication, HttpServletResponse response) throws IOException {
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

    @GetMapping("/export/cnfis")
    @ResponseBody
    public void exportCnfisResults(Authentication authentication, HttpServletResponse response) throws IOException {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            response.sendRedirect("/login");
            return;
        }

        UserWorkbookExportResult exportResult = userReportFacade.buildLegacyUserCnfisWorkbookExport(currentUser.getEmail());
        if (exportResult.status() == UserWorkbookExportStatus.NOT_FOUND) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(exportResult.contentType());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + exportResult.fileName() + "\"");
        response.getOutputStream().write(exportResult.workbookBytes());
    }


    @GetMapping("/individualReports")
    public String viewReports(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }

        UserReportsListViewModel viewModel = userReportFacade.buildIndividualReportsListView(currentUser.getEmail());
        model.addAttribute("individualReports", viewModel.individualReports());
        model.addAttribute("user", currentUser);
        return "user/individualReports";
    }


    @GetMapping("/individualReports/view/{id}")
    public String viewIndividualReport(Model model, Authentication authentication, @PathVariable("id") String id) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }

        Optional<IndividualReportRunDto> runOpt = userIndividualReportRunService.getOrCreateLatestRun(currentUser.getEmail(), id);
        Optional<ro.uvt.pokedex.core.model.reporting.IndividualReport> reportOpt = userReportFacade.findIndividualReportById(id);
        if (runOpt.isEmpty() || reportOpt.isEmpty()) {
            return "redirect:/error";
        }

        IndividualReportRunDto run = runOpt.get();
        ro.uvt.pokedex.core.model.reporting.IndividualReport report = reportOpt.get();
        Map<ro.uvt.pokedex.core.model.reporting.Indicator, Double> indicatorScores = new HashMap<>();
        for (ro.uvt.pokedex.core.model.reporting.Indicator indicator : report.getIndicators()) {
            indicatorScores.put(indicator, run.indicatorScoresByIndicatorId().getOrDefault(indicator.getId(), 0.0));
        }

        model.addAttribute("report", report);
        model.addAttribute("indicatorScores", indicatorScores);
        model.addAttribute("criterionScores", run.criteriaScores());
        model.addAttribute("runMetaId", run.runId());
        model.addAttribute("runMetaCreatedAt", run.createdAt());
        model.addAttribute("runMetaSource", run.source());

        model.addAttribute("user", currentUser);
        return "user/individualReport-view";
    }

    @PostMapping("/individualReports/view/{id}/refresh")
    public String refreshIndividualReport(Model model, Authentication authentication, @PathVariable("id") String id) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }
        userIndividualReportRunService.refreshRun(currentUser.getEmail(), id);
        return "redirect:/user/individualReports/view/" + id;
    }
    @GetMapping("/rankings/{id}")
    public String showRankingPage(@PathVariable String id) {
        return "redirect:/rankings/wos/" + id;
    }

    @PostMapping("/profile/save")
    public String saveResearchProfile(@ModelAttribute Researcher researcher,
                                      Authentication authentication,
                                      RedirectAttributes redirectAttributes) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // Redirect to login if user is not authenticated
        }

        // Save the Researcher in the database
        Researcher savedResearcher = researcherService.saveResearcher(researcher);

        // Link the Researcher with the User account
        currentUser.setResearcherId(savedResearcher.getId());
        userService.updateUser(currentUser.getEmail(), currentUser);

        // Add a success message to RedirectAttributes
        redirectAttributes.addFlashAttribute("successMessage", "Research profile updated successfully.");

        // Redirect back to the profile page
        return "redirect:/user/profile";
    }

    private int computeHIndex(List<Publication> publications) {
        int n = publications.size();
        int[] citationCounts = new int[n + 1];

        // Count citations for each publication
        for (Publication pub : publications) {
            int citedByCount = pub.getCitedbyCount();
            if (citedByCount > n) {
                citationCounts[n]++;
            } else {
                citationCounts[citedByCount]++;
            }
        }

        // Compute the h-index
        int totalPapers = 0;
        for (int i = n; i >= 0; i--) {
            totalPapers += citationCounts[i];
            if (totalPapers >= i) {
                return i;
            }
        }

        return 0;
    }
    // File: src/main/java/ro/uvt/pokedex/core/view/UserViewController.java
    @GetMapping("/publications/exportCNFIS2025")
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
