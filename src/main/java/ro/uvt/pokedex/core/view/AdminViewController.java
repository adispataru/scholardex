package ro.uvt.pokedex.core.view;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import org.springframework.beans.factory.ObjectProvider;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAdminReadPort;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.AdminInstitutionReportFacade;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsExportViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.ScholardexCitationsView;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationSearchView;
import ro.uvt.pokedex.core.service.application.model.WosEnrichmentRunSummaryDto;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminViewController {
    private static final Logger log = LoggerFactory.getLogger(AdminViewController.class);

    private final UserService userService;
    private final ResearcherService researcherService;
    private final AdminCatalogFacade adminCatalogFacade;
    private final ObjectProvider<PostgresScholardexAdminReadPort> postgresScholardexAdminReadPortProvider;
    private final AdminInstitutionReportFacade adminInstitutionReportFacade;
    private final RankingMaintenanceFacade rankingMaintenanceFacade;
    private final String Country = "Romania";

    @GetMapping()
    public String showDashboard(Model model) {

        return "admin/dashboard"; // Returns the users.html template
    }

    @GetMapping("/users")
    public String showUsersPage(Model model) {
        List<User> users = userService.getAllUsers();
        model.addAttribute("users", users);
        model.addAttribute("allRoles", Arrays.asList(UserRole.values())); // Adjust based on how you retrieve all roles

        return "admin/users";
    }

    @GetMapping("/researchers")
    public String showResearchersPage(Model model) {
        List<ro.uvt.pokedex.core.model.Researcher> researchers = researcherService.findAllResearchers();
        model.addAttribute("researchers", researchers);
        return "admin/researchers";
    }

    @GetMapping("/institutions")
    public String showInstitutionsPage(Model model, @RequestParam(value = "afname", defaultValue = "vest") String afname) {
        List<Institution> institutions = adminCatalogFacade.listInstitutions();
        model.addAttribute("institutions", institutions);
        List<Affiliation> allByCountry = adminCatalogFacade.listAffiliationsByNameContains(afname);
        model.addAttribute("allAffiliations", allByCountry);
        model.addAttribute("institution", new Institution());
        return "admin/institutions";
    }

    @PostMapping("/institutions/create")
    public String createIndividualReport(@ModelAttribute Institution institution, RedirectAttributes redirectAttributes) {
        adminCatalogFacade.saveInstitution(institution);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report created successfully.");
        return "redirect:/admin/institutions";
    }

    @GetMapping("/institutions/edit/{id}")
    public String editInstitution(@PathVariable String id, Model model) {
        Institution institution = adminCatalogFacade.findInstitutionById(id).orElse(null);
        model.addAttribute("institution", institution);
        List<Affiliation> allByCountry = adminCatalogFacade.listAffiliationsByCountry(Country);
        model.addAttribute("allAffiliations", allByCountry);
        return "admin/edit-institutions";
    }

    @GetMapping("/institutions/{id}/publications")
    public String viewInstitutionPublication(@PathVariable String id, Model model) {
        Optional<AdminInstitutionPublicationsViewModel> viewModel = adminInstitutionReportFacade.buildInstitutionPublicationsView(id);
        if (viewModel.isEmpty()) {
            return "redirect:/admin/institutions";
        }
        AdminInstitutionPublicationsViewModel vm = viewModel.get();
        model.addAttribute("authorMap", vm.authorMap());
        model.addAttribute("publicationsByYear", vm.publicationsByYear());
        model.addAttribute("publicationsCountByYear", vm.publicationsCountByYear());
        model.addAttribute("individualReports", vm.individualReports());
        model.addAttribute("forumMap", vm.forumMap());
        model.addAttribute("publications", vm.publications());
        model.addAttribute("institution", vm.institution());
        model.addAttribute("publications", vm.publications());
        return "admin/institution-publications";
    }

    @GetMapping("/institutions/{id}/publications/exportExcel")
    @ResponseBody
    public void exportInstitutionPublicationsExcel(@PathVariable("id") String institutionId, HttpServletResponse response) throws IOException {
        Optional<AdminInstitutionPublicationsExportViewModel> exportViewModel =
                adminInstitutionReportFacade.buildInstitutionPublicationsExport(institutionId);
        if (exportViewModel.isEmpty()) {
            return;
        }
        AdminInstitutionPublicationsExportViewModel vm = exportViewModel.get();

        // 3. Prepare Excel workbook
        try (Workbook workbook = new XSSFWorkbook()) {
            // Publications sheet
            Sheet pubSheet = workbook.createSheet("Publications");
            Row pubHeader = pubSheet.createRow(0);
            pubHeader.createCell(0).setCellValue("eID");
            pubHeader.createCell(1).setCellValue("doi");
            pubHeader.createCell(2).setCellValue("Title");
            pubHeader.createCell(3).setCellValue("Year");
            pubHeader.createCell(4).setCellValue("Authors");
            pubHeader.createCell(5).setCellValue("Forum");
            pubHeader.createCell(6).setCellValue("Citations");

            int pubRowNum = 1;
            for (Publication pub : vm.publications()) {
                log.debug("Processing publication for institution export: authors={}, forum={}", pub.getAuthors(), pub.getForum());
                Row row = pubSheet.createRow(pubRowNum++);
                row.createCell(0).setCellValue(pub.getEid());
                row.createCell(1).setCellValue(pub.getDoi());
                row.createCell(2).setCellValue(pub.getTitle());
                row.createCell(3).setCellValue(PersistenceYearSupport.extractYearString(pub.getCoverDate(), pub.getId(), log));
                row.createCell(4).setCellValue(pub.getAuthors().stream().map(a -> vm.authorMap().containsKey(a)? vm.authorMap().get(a).getName(): "").collect(Collectors.joining(",")));
                row.createCell(5).setCellValue(vm.forumMap().get(pub.getForum()) != null ? vm.forumMap().get(pub.getForum()).getPublicationName() : "");
                row.createCell(6).setCellValue(pub.getCitedbyCount());
            }

            // Citations sheet
            Sheet citSheet = workbook.createSheet("Citations");
            Row citHeader = citSheet.createRow(0);
            citHeader.createCell(0).setCellValue("Cited Publication eID");
            citHeader.createCell(1).setCellValue("Cited Publication doi");
            citHeader.createCell(2).setCellValue("Cited Publication Title");
            citHeader.createCell(3).setCellValue("Citing Publication eID");
            citHeader.createCell(4).setCellValue("Citing Publication doi");
            citHeader.createCell(5).setCellValue("Citing Publication Title");
            citHeader.createCell(6).setCellValue("Year");
            citHeader.createCell(7).setCellValue("Authors");
            citHeader.createCell(8).setCellValue("Forum");
            citHeader.createCell(9).setCellValue("Citations");

            int citRowNum = 1;
            for (Publication pub : vm.publications()) {
                for(Publication citing : vm.citationMap().getOrDefault(pub.getId(), Collections.emptyList())) {
                    Row row = citSheet.createRow(citRowNum++);
                    row.createCell(0).setCellValue(pub.getEid());
                    row.createCell(1).setCellValue(pub.getDoi());
                    row.createCell(2).setCellValue(pub.getTitle());
                    row.createCell(3).setCellValue(citing.getEid());
                    row.createCell(4).setCellValue(citing.getDoi());
                    row.createCell(5).setCellValue(citing.getTitle());
                    row.createCell(6).setCellValue(PersistenceYearSupport.extractYearString(citing.getCoverDate(), citing.getId(), log));
                    row.createCell(7).setCellValue(citing.getAuthors().stream().map(a -> vm.authorMap().containsKey(a)? vm.authorMap().get(a).getName(): "").collect(Collectors.joining(",")));
                    row.createCell(8).setCellValue(vm.forumMap().get(citing.getForum()) != null ? vm.forumMap().get(citing.getForum()).getPublicationName() : "" );
                    row.createCell(9).setCellValue(citing.getCitedbyCount());
                }
                Row row = citSheet.createRow(citRowNum++);
            }

            // 4. Write to response
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"institution_publications.xlsx\"");
            workbook.write(response.getOutputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/institutions/update")
    public String updateInstitution(@ModelAttribute Institution institution, RedirectAttributes redirectAttributes) {
        adminCatalogFacade.saveInstitution(institution);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report updated successfully.");
        return "redirect:/admin/institutions";
    }

    @GetMapping("/indicators")
    public String getCriterion(Model model) {
        List<Indicator> all = adminCatalogFacade.listIndicators();
        List<Activity> activities = adminCatalogFacade.listActivities();
        model.addAttribute("indicators", all);

        List<Indicator.Strategy> scoringStrategies =  Arrays.asList(
                Indicator.Strategy.values()
        );

        List<Indicator.Type> types = List.of(Indicator.Type.values());
        List<Domain> domains = adminCatalogFacade.listDomains();

        Map<String, String> activityDescriptions = getActivityDescriptions(activities);

        model.addAttribute("activities", activities);
        model.addAttribute("activityDescriptions", activityDescriptions);
        model.addAttribute("scoringStrategies", scoringStrategies);
        model.addAttribute("types", types);
        model.addAttribute("indicator", new Indicator());
        model.addAttribute("domains", domains);
        model.addAttribute("selectors", Indicator.Selector.values());
        return "admin/indicators";
    }

    private static Map<String, String> getActivityDescriptions(List<Activity> activities) {
        Map<String,String> activityDescriptions = activities.stream()
                .collect(Collectors.toMap(
                        Activity::getId,
                        act -> {
                            String body = "";
                            if(act.getFields() != null) {
                                body += "Fields: ";
                                body = act.getFields().stream()
                                        .map(f -> f.getName()
                                                + (f.isNumber()
                                                ? " (numeric)"
                                                : f.getAllowedValues() != null ? " [" + String.join(", ", f.getAllowedValues()) + "]" : ""))
                                        .collect(Collectors.joining("; "));
                            }
                            if(act.getReferenceFields() != null) {
                                body += "Ref. Fields: ";
                                body += act.getReferenceFields().stream()
                                        .map(Enum::name)
                                        .collect(Collectors.joining("; "));
                            }
                            return body;
                        }
                ));
        return activityDescriptions;
    }

    @PostMapping("/indicators/create")
    public String createIndicator(@ModelAttribute Indicator indicator) {
        adminCatalogFacade.saveIndicator(indicator);
        return "redirect:/admin/indicators";
    }

    @PostMapping("/indicators/update")
    public String updateCriterion(@ModelAttribute Indicator indicator) {
        adminCatalogFacade.saveIndicator(indicator);
        return "redirect:/admin/indicators";
    }

    @GetMapping("/indicators/edit/{id}")
    public String editIndicator(@PathVariable String id, Model model) {

        Optional<Indicator> byId = adminCatalogFacade.findIndicatorById(id);
        if(byId.isPresent()) {
            model.addAttribute("indicator", byId.get());
            List<Indicator.Strategy> scoringStrategies =  Arrays.asList(
                    Indicator.Strategy.values()
            );
            List<Activity> activities = adminCatalogFacade.listActivities();

            List<Indicator.Type> types = List.of(Indicator.Type.values());
            List<Domain> domains = adminCatalogFacade.listDomains();

            Map<String, String> activityDescriptions = getActivityDescriptions(activities);

            model.addAttribute("activities", activities);
            model.addAttribute("activityDescriptions", activityDescriptions);
            model.addAttribute("scoringStrategies", scoringStrategies);
            model.addAttribute("types", types);
            model.addAttribute("domains", domains);
            model.addAttribute("selectors", Indicator.Selector.values());
            return "admin/indicators-edit";
        }else {
            return "redirect:/admin/indicators";
        }
    }

    @PostMapping("/indicators/duplicate/{id}")
    public String duplicateIndicator(@PathVariable String id, Model model) {

        Optional<Indicator> byId = adminCatalogFacade.duplicateIndicator(id);
        if(byId.isPresent()) {
            return "redirect:/admin/indicators/edit/" + byId.get().getId();
        }else {
            return "redirect:/admin/indicators";
        }
    }

    @PostMapping("/indicators/delete/{id}")
    public String deleteIndicator(@PathVariable String id) {
        adminCatalogFacade.deleteIndicator(id);
        return "redirect:/admin/indicators";
    }

    @GetMapping("/domains")
    public String getDomains(Model model) {
        List<Domain> domains = adminCatalogFacade.listDomains();
        List<String> allWosCategories = adminCatalogFacade.listWosCategories();
        model.addAttribute("domains", domains);
        model.addAttribute("allWosCategories", allWosCategories);
        model.addAttribute("domain", new Domain());
        return "admin/domains"; // Thymeleaf template name
    }

    @GetMapping("/domains/edit/{id}")
    public String editDomain(@PathVariable String id, Model model) {
        Optional<Domain> byId = adminCatalogFacade.findDomainById(id);
        if(byId.isPresent()) {
            List<String> allWosCategories = adminCatalogFacade.listWosCategories();
            model.addAttribute("domain", byId.get());
            model.addAttribute("allWosCategories", allWosCategories);
            return "admin/domains-edit";
        }else{
            return "redirect:/admin/domains";
        }

    }

    @PostMapping("/domains/create")
    public String createDomain(@ModelAttribute Domain domain) {
        adminCatalogFacade.saveDomain(domain);
        return "redirect:/admin/domains";
    }

    @PostMapping("/domains/update")
    public String updateDomain(@ModelAttribute Domain domain) {
        adminCatalogFacade.saveDomain(domain);
        return "redirect:/admin/domains";
    }

    @PostMapping("/domains/delete/{name}")
    public String deleteDomain(@PathVariable String name) {
        adminCatalogFacade.deleteDomain(name);
        return "redirect:/admin/domains";
    }

    @PostMapping("/institutions/delete/{name}")
    public String deleteInstitution(@PathVariable String name) {
        adminCatalogFacade.deleteInstitution(name);
        return "redirect:/admin/institutions";
    }

    @GetMapping("/scholardex/forums")
    public String showScholardexForumsPage() {
        return "admin/scholardex-forums";
    }

    @GetMapping("/scholardex/forums/edit/{id}")
    public String editScholardexForumPage(Model model, @PathVariable String id) {
        Optional<Forum> venue = adminCatalogFacade.findScopusVenueById(id);
        venue.ifPresent(v-> model.addAttribute("forum", v));
        return "admin/scholardex-editForum";
    }

    @PostMapping("/scholardex/forums/edit/{id}")
    public String updateScholardexForum(@ModelAttribute("forum") Forum forum, RedirectAttributes redirectAttributes) {
        adminCatalogFacade.saveScopusVenue(forum);
        redirectAttributes.addFlashAttribute("message", "Forum updated successfully!");
        return "redirect:/admin/scholardex/forums/edit/" + forum.getId();
    }

    @GetMapping("/scholardex/authors")
    public String showScholardexAuthorsPage() {
        return "admin/scholardex-authors";
    }

    @GetMapping("/scholardex/authors/edit/{id}")
    public String editScholardexAuthorsPage(@PathVariable String id) {
        return "redirect:/admin/scholardex/authors";
    }

    @PostMapping("/scholardex/authors/edit/{id}")
    public String updateScholardexAuthor(@ModelAttribute("author") Author author, RedirectAttributes redirectAttributes) {
        return "redirect:/admin/scholardex/authors";
    }

    @GetMapping("/scholardex/affiliations")
    public String showScholardexAffiliationsPage() {
        return "admin/scholardex-affiliations";
    }

    @GetMapping("/scholardex/affiliations/edit/{id}")
    public String editScholardexAffiliationsPage(Model model, @PathVariable String id) {
        Optional<Affiliation> byId = adminCatalogFacade.findScopusAffiliationById(id);
        byId.ifPresent(v-> model.addAttribute("affiliation", v));
        return "admin/scholardex-editAffiliation";
    }

    @PostMapping("/scholardex/affiliations/edit/{id}")
    public String updateScholardexAffiliations(@ModelAttribute("affiliation") Affiliation affiliation, RedirectAttributes redirectAttributes, @PathVariable String id) {
        adminCatalogFacade.saveScopusAffiliation(affiliation);
        redirectAttributes.addFlashAttribute("message", "Affiliation updated successfully!");
        return "redirect:/admin/scholardex/affiliations";
    }

    @GetMapping("/scholardex/publications/search")
    public String searchScholardexPublications(@RequestParam String authorName,
                                     @RequestParam String paperTitle,
                                     Model model) {
        PostgresScholardexAdminReadPort adminReadPort = postgresScholardexAdminReadPortProvider.getIfAvailable();
        if (adminReadPort == null) {
            throw new IllegalStateException("Postgres admin read port is not available.");
        }
        ScholardexPublicationSearchView viewModel = adminReadPort.buildPublicationSearchView(paperTitle);
        model.addAttribute("authorMap", viewModel.authorMap());
        model.addAttribute("publications", viewModel.publications());
        return "admin/scholardex-publications-search";
    }

    @GetMapping("/scholardex/publications")
    public String showScholardexPublicationsPage() {
        return "admin/scholardex-publications";
    }

    @GetMapping("/scholardex/publications/citations")
    public String showScholardexPublicationCitationsPage(Model model, @RequestParam("id") String id) {
        PostgresScholardexAdminReadPort adminReadPort = postgresScholardexAdminReadPortProvider.getIfAvailable();
        if (adminReadPort == null) {
            throw new IllegalStateException("Postgres admin read port is not available.");
        }
        Optional<ScholardexCitationsView> viewModel = adminReadPort.buildPublicationCitationsView(id);
        viewModel.ifPresent(vm -> {
            model.addAttribute("citations", vm.citations());
            model.addAttribute("publication", vm.publication());
            model.addAttribute("forum", vm.publicationForum());
            model.addAttribute("authorMap", vm.authorMap());
            model.addAttribute("forumMap", vm.forumMap());
        });
        return "admin/scholardex-citations";
    }

    @PostMapping("/rankings/wos/computePositionsForKnownQuarters")
    public String computeMissingRanks(RedirectAttributes redirectAttributes) {
        try {
            rankingMaintenanceFacade.computePositionsForKnownQuarters();
            redirectAttributes.addFlashAttribute("successMessage", "Legacy WoS operation completed.");
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/forums?wos=indexed";
    }

    @PostMapping("/rankings/wos/computeQuartersAndRankingsWhereMissing")
    public String computeMissingQuartersAndRanks(RedirectAttributes redirectAttributes) {
        try {
            rankingMaintenanceFacade.computeQuartersAndRankingsWhereMissing();
            redirectAttributes.addFlashAttribute("successMessage", "Legacy WoS operation completed.");
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/forums?wos=indexed";
    }

    @PostMapping("/rankings/wos/mergeDuplicateRankings")
    public String mergeDuplicateRankings(RedirectAttributes redirectAttributes) {
        try {
            rankingMaintenanceFacade.mergeDuplicateRankings();
            redirectAttributes.addFlashAttribute("successMessage", "Legacy WoS operation completed.");
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/forums?wos=indexed";
    }

    @PostMapping("/rankings/wos/rebuildProjections")
    public String rebuildWosProjections(RedirectAttributes redirectAttributes) {
        var result = rankingMaintenanceFacade.rebuildWosProjections();
        redirectAttributes.addFlashAttribute("successMessage",
                "WoS projections rebuilt. Imported=" + result.getImportedCount()
                        + ", Skipped=" + result.getSkippedCount()
                        + ", Errors=" + result.getErrorCount());
        return "redirect:/forums?wos=indexed";
    }

    @PostMapping("/rankings/wos/ensureIndexes")
    public String ensureWosIndexes(RedirectAttributes redirectAttributes) {
        var result = rankingMaintenanceFacade.ensureWosIndexes();
        redirectAttributes.addFlashAttribute("successMessage",
                "WoS indexes ensured. Created=" + result.created().size()
                        + ", Present=" + result.present().size()
                        + ", Invalid=" + result.invalid().size()
                        + ", Errors=" + result.errors().size());
        return "redirect:/forums?wos=indexed";
    }

    @PostMapping("/rankings/wos/runBigBangMigration")
    public String runWosBigBangMigration(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(name = "sourceVersion", required = false) String sourceVersion,
            RedirectAttributes redirectAttributes
    ) {
        try {
            var result = rankingMaintenanceFacade.runWosBigBangMigration(dryRun, sourceVersion);
            WosEnrichmentRunSummaryDto enrichmentSummary =
                    WosEnrichmentRunSummaryDto.fromStep(result.enrichCategoryRankings(), null, null);
            String mode = dryRun ? "dry-run" : "full-run";
            String successMessage =
                    "WoS big-bang " + mode
                            + " complete. Ingest[p=" + result.ingest().processed()
                            + ", i=" + result.ingest().imported()
                            + ", u=" + result.ingest().updated()
                            + ", s=" + result.ingest().skipped()
                            + ", e=" + result.ingest().errors()
                            + "], Facts[p=" + result.buildFacts().processed()
                            + ", i=" + result.buildFacts().imported()
                            + ", u=" + result.buildFacts().updated()
                            + ", s=" + result.buildFacts().skipped()
                            + ", e=" + result.buildFacts().errors()
                            + "], Enrichment[processed=" + enrichmentSummary.processed()
                            + ", computed=" + enrichmentSummary.computed()
                            + ", preserved=" + enrichmentSummary.preserved()
                            + ", failed=" + enrichmentSummary.failed()
                            + ", skipped=" + enrichmentSummary.skipped()
                            + "], Projections[p=" + result.buildProjections().processed()
                            + ", i=" + result.buildProjections().imported()
                            + ", u=" + result.buildProjections().updated()
                            + ", s=" + result.buildProjections().skipped()
                            + ", e=" + result.buildProjections().errors()
                            + "], Verify[events=" + result.verification().importEvents()
                            + ", metricFacts=" + result.verification().metricFacts()
                            + ", categoryFacts=" + result.verification().categoryFacts()
                            + ", rankingRows=" + result.verification().rankingViewRows()
                            + ", scoringRows=" + result.verification().scoringViewRows()
                            + ", parserErrors=" + result.verification().parserErrors()
                            + ", parityPassed=" + result.verification().parityPassed()
                            + ", parityMismatches=" + result.verification().parityMismatchCount()
                            + ", parityAllowlisted=" + result.verification().parityAllowlistedMismatchCount()
                            + "].";
            if (result.dryRun() && result.ingest().note() != null && !result.ingest().note().isBlank()) {
                successMessage = successMessage + " " + result.ingest().note() + ".";
            }
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "WoS big-bang migration failed: " + e.getMessage());
        }
        return "redirect:/forums?wos=indexed";
    }

    @PostMapping("/users/lock/{email}")
    public String lockUser(@PathVariable String email) {
        userService.lockUser(email);
        return "redirect:/admin/users";
    }

    @PostMapping("/users/create")
    public String createUser(@RequestParam String email,
                             @RequestParam String password,
                             @RequestParam List<String> roles, RedirectAttributes redirectAttributes) {
        if (!userService.areValidRoleNames(roles)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Invalid role selection.");
            return "redirect:/admin/users";
        }
        Optional<User> user = userService.createUser(email, password, roles);
        if(user.isPresent()){
            // Add a success message to redirect attributes
            redirectAttributes.addFlashAttribute("successMessage", "User successfully created.");
        } else {
            // Add an error message to redirect attributes
            redirectAttributes.addFlashAttribute("errorMessage", "Error creating user: " + email + " already exists!");
        }
        return "redirect:/admin/users"; // Redirect to the view page regardless of outcome
    }

    @PostMapping("/users/delete/{email}")
    public String deleteUserPost(@PathVariable String email) {
        userService.deleteUser(email);
        return "redirect:/admin/users";
    }

    @PostMapping("/users/updateRoles")
    public String updateRoles(@RequestParam String email, @RequestParam(required = false) List<String> roles) {
        userService.updateUserRoles(email, roles);
        return "redirect:/admin/users";
    }

}
