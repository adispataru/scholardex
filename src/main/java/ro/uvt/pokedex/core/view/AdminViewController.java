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
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.*;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.*;
import ro.uvt.pokedex.core.repository.scopus.*;
import ro.uvt.pokedex.core.service.application.AdminScopusFacade;
import ro.uvt.pokedex.core.service.application.AdminInstitutionReportFacade;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsExportViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminViewController {
    private static final Logger log = LoggerFactory.getLogger(AdminViewController.class);

    private final UserService userService;
    private final ResearcherService researcherService;
    private final ScopusForumRepository scopusVenueRepository;
    private final ScopusAuthorRepository scopusAuthorRepository;
    private final ScopusAffiliationRepository scopusAffiliationRepository;
    private final ScopusPublicationRepository scopusPublicationRepository;
    private final ScopusForumRepository scopusForumRepository;
    private final ScopusCitationRepository scopusCitationRepository;
    private final ArtisticEventRepository artisticEventRepository;
    private final RankingRepository rankingRepository;
    private final CoreConferenceRankingRepository coreConferenceRankingRepository;
    private final CacheService cacheService;
    private final IndicatorRepository indicatorRepository;
    private final DomainRepository domainRepository;
    private final InstitutionRepository institutionRepository;
    private final ActivityRepository activityRepository;
    private final IndividualReportRepository individualReportRepository;
    private final AdminScopusFacade adminScopusFacade;
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
        List<Institution> institutions = institutionRepository.findAll();
        model.addAttribute("institutions", institutions);
        List<Affiliation> allByCountry = scopusAffiliationRepository.findAllByNameContains(afname);
        allByCountry.sort(Comparator.comparing(Affiliation::getName));
        model.addAttribute("allAffiliations", allByCountry);
        model.addAttribute("institution", new Institution());
        return "admin/institutions";
    }

    @PostMapping("/institutions/create")
    public String createIndividualReport(@ModelAttribute Institution institution, RedirectAttributes redirectAttributes) {
        institutionRepository.save(institution);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report created successfully.");
        return "redirect:/admin/institutions";
    }

    @GetMapping("/institutions/edit/{id}")
    public String editInstitution(@PathVariable String id, Model model) {
        Institution institution = institutionRepository.findById(id).orElse(null);
        model.addAttribute("institution", institution);List<Affiliation> allByCountry = scopusAffiliationRepository.findAllByCountry(Country);
        allByCountry.sort(Comparator.comparing(Affiliation::getName));
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
        institutionRepository.save(institution);
        redirectAttributes.addFlashAttribute("successMessage", "Individual Report updated successfully.");
        return "redirect:/admin/institutions";
    }

    @GetMapping("/indicators")
    public String getCriterion(Model model) {
        List<Indicator> all = indicatorRepository.findAll();
        List<Activity> activities = activityRepository.findAll();
        model.addAttribute("indicators", all);

        List<Indicator.Strategy> scoringStrategies =  Arrays.asList(
                Indicator.Strategy.values()
        );

        List<Indicator.Type> types = List.of(Indicator.Type.values());
        List<Domain> domains = domainRepository.findAll();

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
        indicatorRepository.save(indicator);
        return "redirect:/admin/indicators";
    }

    @PostMapping("/indicators/update")
    public String updateCriterion(@ModelAttribute Indicator indicator) {
        indicatorRepository.save(indicator);
        return "redirect:/admin/indicators";
    }

    @GetMapping("/indicators/edit/{id}")
    public String editIndicator(@PathVariable String id, Model model) {

        Optional<Indicator> byId = indicatorRepository.findById(id);
        if(byId.isPresent()) {
            model.addAttribute("indicator", byId.get());
            List<Indicator.Strategy> scoringStrategies =  Arrays.asList(
                    Indicator.Strategy.values()
            );
            List<Activity> activities = activityRepository.findAll();

            List<Indicator.Type> types = List.of(Indicator.Type.values());
            List<Domain> domains = domainRepository.findAll();

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

    @GetMapping("/indicators/duplicate/{id}")
    public String duplicateIndicator(@PathVariable String id, Model model) {

        Optional<Indicator> byId = indicatorRepository.findById(id);
        if(byId.isPresent()) {
            Indicator indicator = byId.get();
            indicator.setId(null);
            indicator.setName(indicator.getName() + " (copy)");
            indicator = indicatorRepository.save(indicator);

            return "redirect:/admin/indicators/edit/" + indicator.getId();
        }else {
            return "redirect:/admin/indicators";
        }
    }

    @GetMapping("/indicators/delete/{id}")
    public String deleteIndicator(@PathVariable String id) {
        indicatorRepository.deleteById(id);
        return "redirect:/admin/indicators";
    }

    @GetMapping("/domains")
    public String getDomains(Model model) {
        List<Domain> domains = domainRepository.findAll();
        List<String> allWosCategories = new ArrayList<>(cacheService.getWosCategories());
        Collections.sort(allWosCategories);
        model.addAttribute("domains", domains);
        model.addAttribute("allWosCategories", allWosCategories);
        model.addAttribute("domain", new Domain());
        return "admin/domains"; // Thymeleaf template name
    }

    @GetMapping("/domains/edit/{id}")
    public String editDomain(@PathVariable String id, Model model) {
        Optional<Domain> byId = domainRepository.findById(id);
        if(byId.isPresent()) {
            List<String> allWosCategories = new ArrayList<>(cacheService.getWosCategories());
            Collections.sort(allWosCategories);
            model.addAttribute("domain", byId.get());
            model.addAttribute("allWosCategories", allWosCategories);
            return "admin/domains-edit";
        }else{
            return "redirect:/admin/domains";
        }

    }

    @PostMapping("/domains/create")
    public String createDomain(@ModelAttribute Domain domain) {
        domainRepository.save(domain);
        return "redirect:/admin/domains";
    }

    @PostMapping("/domains/update")
    public String updateDomain(@ModelAttribute Domain domain) {
        domainRepository.save(domain);
        return "redirect:/admin/domains";
    }

    @GetMapping("/domains/delete/{name}")
    public String deleteDomain(@PathVariable String name) {
        domainRepository.deleteById(name);
        return "redirect:/admin/domains";
    }

    @GetMapping("/scopus/venues")
    public String showScopusVenuesPage(Model model) {
        List<Forum> researchers = scopusVenueRepository.findAll();
        model.addAttribute("venues", researchers);
        return "admin/scopus-venues";
    }

    @GetMapping("/scopus/venues/edit/{id}")
    public String editScopusVenuePage(Model model, @PathVariable String id) {
        Optional<Forum> venue = scopusVenueRepository.findById(id);
        venue.ifPresent(v-> model.addAttribute("forum", v));
        return "admin/scopus-editVenues";
    }

    @PostMapping("/scopus/venues/edit/{id}")
    public String updateScopusVenue(@ModelAttribute("venue") Forum forum, RedirectAttributes redirectAttributes) {
        scopusVenueRepository.save(forum);
        redirectAttributes.addFlashAttribute("message", "Venue updated successfully!");
        return "redirect:/admin/scopus/venues/edit/"+forum.getId();
    }

    @GetMapping("/scopus/authors")
    public String showScopusAuthorsPage(Model model, @RequestParam(value = "afid", defaultValue = "60000434") String afid) {
        Optional<Affiliation> byId = scopusAffiliationRepository.findById(afid);
        byId.ifPresent(af -> {
            List<Author> all = scopusAuthorRepository.findAllByAffiliationsContaining(af);
            model.addAttribute("authors", all);
        });

        return "admin/scopus-authors";
    }

    @GetMapping("/scopus/authors/edit/{id}")
    public String editScopusAuthorsPage(Model model, @PathVariable String id) {
        Optional<Author> byId = scopusAuthorRepository.findById(id);
        byId.ifPresent(a-> {
            AtomicInteger numCitations = new AtomicInteger();
            model.addAttribute("author", a);
            List<Publication> pubs = scopusPublicationRepository.findAllByAuthorsContaining(a.getId());
            pubs.forEach(p-> numCitations.addAndGet(p.getCitedbyCount()));
            model.addAttribute("publications", pubs);
            model.addAttribute("citationCount", numCitations.get());
        });

        return "admin/scopus-editAuthor";
    }

    @PostMapping("/scopus/authors/edit/{id}")
    public String updateScopusAuthor(@ModelAttribute("author") Author author, RedirectAttributes redirectAttributes) {
        scopusAuthorRepository.save(author);
        redirectAttributes.addFlashAttribute("message", "Author updated successfully!");
        return "redirect:/admin/scopus-venues";
    }

    @GetMapping("/scopus/affiliations")
    public String showScopusAffiliationsPage(Model model) {
        List<Affiliation> all = scopusAffiliationRepository.findAll();
        model.addAttribute("affiliations", all);
        return "admin/scopus-affiliations";
    }

    @GetMapping("/scopus/affiliations/edit/{id}")
    public String editScopusAffiliationsPage(Model model, @PathVariable String id) {
        Optional<Affiliation> byId = scopusAffiliationRepository.findById(id);
        byId.ifPresent(v-> model.addAttribute("affiliation", v));
        return "admin/scopus-editAffiliations";
    }

    @PostMapping("/scopus/affiliations/edit/{id}")
    public String updateScopusAffiliations(@ModelAttribute("affiliation") Affiliation affiliation, RedirectAttributes redirectAttributes, @PathVariable String id) {
        scopusAffiliationRepository.save(affiliation);
        redirectAttributes.addFlashAttribute("message", "Affiliation updated successfully!");
        return "redirect:/admin/scopus-affiliations";
    }

    @GetMapping("/scopus/publications/search")
    public String searchPublications(@RequestParam String authorName,
                                     @RequestParam String paperTitle,
                                     Model model) {
        AdminScopusPublicationSearchViewModel viewModel = adminScopusFacade.buildPublicationSearchView(paperTitle);
        model.addAttribute("authorMap", viewModel.authorMap());
        model.addAttribute("publications", viewModel.publications());
        return "admin/scopus-publications-search";
    }

    @GetMapping("/scopus/publications")
    public String showScopusPublicationsPage() {
        return "admin/scopus-publications";
    }

    @GetMapping("/scopus/publications/citations")
    public String showPublicationCitationsPage(Model model, @RequestParam("id") String id) {
        Optional<AdminScopusCitationsViewModel> viewModel = adminScopusFacade.buildPublicationCitationsView(id);
        viewModel.ifPresent(vm -> {
            model.addAttribute("citations", vm.citations());
            model.addAttribute("publication", vm.publication());
            model.addAttribute("forum", vm.publicationForum());
            model.addAttribute("authorMap", vm.authorMap());
            model.addAttribute("forumMap", vm.forumMap());
        });
        return "admin/scopus-citations";
    }

    @GetMapping("/rankings/wos")
    public String showRankingsPage(Model model) {
        List<WoSRanking> journals = cacheService.getAllRankings();
        model.addAttribute("journals", journals);
        return "admin/rankings";
    }

    @GetMapping("/rankings/events")
    public String showArtsRankingsPage(Model model) {
        List<ArtisticEvent> all = artisticEventRepository.findAll();
        model.addAttribute("artisticEvents", all);
        return "admin/events";
    }

    @PostMapping("/rankings/wos/computePositionsForKnownQuarters")
    public String computeMissingRanks() {
        rankingMaintenanceFacade.computePositionsForKnownQuarters();
        return "redirect:/admin/rankings/wos";
    }

    @PostMapping("/rankings/wos/computeQuartersAndRankingsWhereMissing")
    public String computeMissingQuartersAndRanks() {
        rankingMaintenanceFacade.computeQuartersAndRankingsWhereMissing();
        return "redirect:/admin/rankings/wos";
    }

    @PostMapping("/rankings/wos/mergeDuplicateRankings")
    public String mergeDuplicateRankings() {
        rankingMaintenanceFacade.mergeDuplicateRankings();
        return "redirect:/admin/rankings/wos";
    }

    @GetMapping("/rankings/core")
    public String showCoreRankingsPage(Model model) {
        List<CoreConferenceRanking> all = coreConferenceRankingRepository.findAll();
        model.addAttribute("confs", all);
        return "admin/rankings-core";
    }

    @GetMapping("/rankings/wos/{id}")
    public String showRankingPage(Model model, @PathVariable  String id) {
        Optional<WoSRanking> journals = rankingRepository.findById(id);
        if(journals.isPresent()) {
            WoSRanking ranking = journals.get();
            model.addAttribute("journal", ranking);
            return "admin/rankings-view";
        }
        return "redirect:/admin/rankings/wos";
    }

    @GetMapping("/rankings/core/{id}")
    public String showCoreRankingPage(Model model, @PathVariable  String id) {
        Optional<CoreConferenceRanking> byId = coreConferenceRankingRepository.findById(id);
        if(byId.isPresent()) {
            CoreConferenceRanking ranking = byId.get();
            model.addAttribute("conf", ranking);
            return "admin/rankings-core-view";
        }
        return "redirect:/admin/rankings/core";
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
        User user = userService.createUser(email, password, roles);
        if(user != null){
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
