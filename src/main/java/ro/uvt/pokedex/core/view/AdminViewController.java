package ro.uvt.pokedex.core.view;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DivisionType;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.service.importing.StaffImportService;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;
import ro.uvt.pokedex.core.model.reporting.scoring.Selector;
import ro.uvt.pokedex.core.model.reporting.scoring.YearRangeSpec;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.AdminDashboardService;
import ro.uvt.pokedex.core.service.application.AdminInstitutionReportFacade;
import ro.uvt.pokedex.core.service.application.GroupManagementFacade;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.model.AdminDashboardViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsExportViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.BreadcrumbItem;
import ro.uvt.pokedex.core.service.application.model.StatCardDef;
import ro.uvt.pokedex.core.service.application.model.WosEnrichmentRunSummaryDto;
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
    private final AdminCatalogFacade adminCatalogFacade;
    private final AdminInstitutionReportFacade adminInstitutionReportFacade;
    private final RankingMaintenanceFacade rankingMaintenanceFacade;
    private final AdminDashboardService adminDashboardService;
    private final GroupManagementFacade groupManagementFacade;
    private final StaffImportService staffImportService;
    private final String Country = "Romania";

    @Value("${h07.staff.import.max-bytes:2097152}")
    private long staffImportMaxBytes;
    @Value("${h07.staff.import.allowed-content-types:text/csv,application/vnd.ms-excel}")
    private String staffImportAllowedContentTypes;

    @GetMapping()
    public String showDashboard(Model model) {
        AdminDashboardViewModel dashboard = adminDashboardService.buildDashboard();
        model.addAttribute("dashboard", dashboard);
        return "admin/dashboard";
    }

    @GetMapping("/users")
    public String showUsersPage(Model model) {
        List<User> users = userService.getAllUsers();
        model.addAttribute("users", users);
        model.addAttribute("allRoles", Arrays.asList(UserRole.values()));
        model.addAttribute("allGroups", groupManagementFacade.buildGroupListView().groups());
        model.addAttribute("totalUsers", (long) users.size());
        model.addAttribute("activeUsers", users.stream().filter(u -> !u.isLocked()).count());
        model.addAttribute("usersWithoutProfile", users.stream().filter(u -> u.getResearcherProfile() == null).count());
        model.addAttribute("statCards", buildUserStatCards(users));
        return "admin/users";
    }

    private List<StatCardDef> buildUserStatCards(List<User> users) {
        long activeUsers = users.stream().filter(u -> !u.isLocked()).count();
        long usersWithoutProfile = users.stream().filter(u -> u.getResearcherProfile() == null).count();
        return List.of(
                new StatCardDef("Total Users", users.size(), "primary", "All registered accounts on the platform.", "fa-solid fa-users"),
                new StatCardDef("Active", activeUsers, "success", "Unlocked accounts able to sign in.", "fa-solid fa-user-check"),
                new StatCardDef("Without Profile", usersWithoutProfile, "warning", "Accounts not yet linked to a researcher profile.", "fa-solid fa-user-clock")
        );
    }

    @PostMapping("/users/bulk/assign-group")
    public String bulkAssignGroup(
            @RequestParam(value = "userId", required = false) List<String> userIds,
            @RequestParam(required = false) String groupId,
            RedirectAttributes redirectAttributes
    ) {
        if (userIds != null && !userIds.isEmpty() && groupId != null && !groupId.isBlank()) {
            int added = groupManagementFacade.addMembersToGroup(groupId, userIds);
            redirectAttributes.addFlashAttribute("bulkSuccess", "Added " + added + " user(s) to the group.");
        } else {
            redirectAttributes.addFlashAttribute("bulkError", "No users selected or no group specified.");
        }
        return "redirect:/admin/users";
    }

    @GetMapping("/researchers")
    public String showResearchersPage() {
        return "redirect:/admin/users";
    }

    @GetMapping("/institutions")
    public String showInstitutionsPage(Model model, @RequestParam(value = "afname", defaultValue = "vest") String afname) {
        List<Institution> institutions = adminCatalogFacade.listInstitutions();
        model.addAttribute("institutions", institutions);
        List<ScholardexAffiliationView> allByCountry = adminCatalogFacade.listAffiliationsByNameContains(afname);
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
    public String editInstitution(@PathVariable String id) {
        return "redirect:/admin/institutions";
    }

    @GetMapping("/institutions/{id}/edit-data")
    @ResponseBody
    public ResponseEntity<Institution> getInstitutionEditData(@PathVariable String id) {
        return adminCatalogFacade.findInstitutionById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/institutions/{id}")
    public String viewInstitutionWorkspace(@PathVariable String id, Model model) {
        Optional<AdminInstitutionPublicationsViewModel> viewModel = adminInstitutionReportFacade.buildInstitutionPublicationsView(id);
        if (viewModel.isEmpty()) {
            return "redirect:/admin/institutions";
        }
        AdminInstitutionPublicationsViewModel vm = viewModel.get();
        model.addAttribute("authorMap", vm.authorMap());
        model.addAttribute("publicationsByYear", vm.publicationsByYear());
        model.addAttribute("individualReports", vm.individualReports());
        model.addAttribute("forumMap", vm.forumMap());
        model.addAttribute("publications", vm.publications());
        model.addAttribute("institution", vm.institution());
        return "admin/institution-workspace";
    }

    @GetMapping("/institutions/{id}/publications")
    public String viewInstitutionPublication(@PathVariable String id) {
        return "redirect:/admin/institutions/" + id + "#publications";
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
            for (ScholardexPublicationView pub : vm.publications()) {
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
            for (ScholardexPublicationView pub : vm.publications()) {
                for (ScholardexPublicationView citing : vm.citationMap().getOrDefault(pub.getId(), Collections.emptyList())) {
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

    // H52 slice 11d.5: the dropdown value lists used to come from the legacy
    // nested enums {@code Indicator.Type}/{@code Strategy}/{@code Selector}.
    // After the enums were deleted, the form posts and receives plain Strings
    // that the {@code Indicator} setters route into the typed kind/specs.
    private static final List<String> LEGACY_OUTPUT_TYPES = List.of(
            "PUBLICATIONS", "PUBLICATIONS_MAIN_AUTHOR", "PUBLICATIONS_COAUTHOR",
            "CITATIONS", "CITATIONS_EXCLUDE_SELF",
            "GENERIC_ACTIVITIES",
            "ACTIVITY_FORUM", "ACTIVITY_EVENT", "ACTIVITY_PROJECT", "ACTIVITY_UNIVERSITY"
    );
    private static final List<String> LEGACY_STRATEGIES = java.util.Arrays.stream(
                    ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy.values())
            .map(Enum::name).toList();
    private static final List<String> LEGACY_SELECTORS = List.of("ALL", "TOP_10");

    @GetMapping("/indicators")
    public String getCriterion(Model model) {
        List<Indicator> all = adminCatalogFacade.listIndicators();
        List<Activity> activities = adminCatalogFacade.listActivities();
        model.addAttribute("indicators", all);

        List<Domain> domains = adminCatalogFacade.listDomains();
        Map<String, String> activityDescriptions = getActivityDescriptions(activities);

        model.addAttribute("activities", activities);
        model.addAttribute("activityDescriptions", activityDescriptions);
        model.addAttribute("scoringStrategies", LEGACY_STRATEGIES);
        model.addAttribute("types", LEGACY_OUTPUT_TYPES);
        model.addAttribute("indicator", new IndicatorForm());
        model.addAttribute("domains", domains);
        model.addAttribute("selectors", LEGACY_SELECTORS);
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
    public String createIndicator(@ModelAttribute IndicatorForm indicator) {
        adminCatalogFacade.saveIndicator(indicator.toIndicator());
        return "redirect:/admin/indicators";
    }

    @PostMapping("/indicators/update")
    public String updateCriterion(@ModelAttribute IndicatorForm indicator) {
        adminCatalogFacade.saveIndicator(indicator.toIndicator());
        return "redirect:/admin/indicators";
    }

    /**
     * H52 slice 3: translates the {@code @Version} stale-write into a graceful
     * redirect. Scoped to {@code /admin/indicators*} so other admin resources
     * (which still bubble up the raw 500) are unaffected until they grow their
     * own handlers.
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public String handleIndicatorStaleWrite(org.springframework.dao.OptimisticLockingFailureException ex,
                                            jakarta.servlet.http.HttpServletRequest request,
                                            RedirectAttributes redirectAttributes) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/admin/indicators")) {
            throw ex;
        }
        log.warn("Indicator stale write rejected on {}: {}", uri, ex.getMessage());
        redirectAttributes.addFlashAttribute("errorMessage",
                "This indicator was edited by someone else. Please reload and re-apply your changes.");
        return "redirect:/admin/indicators";
    }

    @GetMapping("/indicators/edit/{id}")
    public String editIndicator(@PathVariable String id, Model model) {

        Optional<Indicator> byId = adminCatalogFacade.findIndicatorById(id);
        if(byId.isPresent()) {
            IndicatorForm form = IndicatorForm.fromIndicator(byId.get());
            model.addAttribute("indicator", form);
            model.addAttribute("adminFormObject", form);
            List<Activity> activities = adminCatalogFacade.listActivities();
            List<Domain> domains = adminCatalogFacade.listDomains();
            Map<String, String> activityDescriptions = getActivityDescriptions(activities);

            model.addAttribute("activities", activities);
            model.addAttribute("activityDescriptions", activityDescriptions);
            model.addAttribute("scoringStrategies", LEGACY_STRATEGIES);
            model.addAttribute("types", LEGACY_OUTPUT_TYPES);
            model.addAttribute("domains", domains);
            model.addAttribute("selectors", LEGACY_SELECTORS);
            model.addAttribute("breadcrumbs", List.of(
                    new BreadcrumbItem("Indicators", "/admin/indicators"),
                    new BreadcrumbItem(byId.get().getName() == null ? "Edit Indicator" : byId.get().getName())
            ));
            return "admin/indicators-edit";
        }else {
            return "redirect:/admin/indicators";
        }
    }

    @lombok.Data
    public static class IndicatorForm {
        private String id;
        private String name;
        private String formula;
        private Domain domain;
        private Activity activity;
        private Long version;
        private String outputType;
        private String scoringStrategy;
        private String yearRange;
        private String scoreYearRange;
        // Default "ALL" so the create-form dropdown shows a selection. An "All"
        // selector derives to null on the domain side (Selector.All.legacyName() is
        // null, which keeps the fingerprint segment empty); the form normalizes that
        // back to "ALL" purely for display so the <select> renders with an option
        // marked selected.
        private String selector = "ALL";

        static IndicatorForm fromIndicator(Indicator indicator) {
            IndicatorForm form = new IndicatorForm();
            form.id = indicator.getId();
            form.name = indicator.getName();
            form.formula = indicator.getFormula();
            form.domain = indicator.getDomain();
            form.activity = indicator.getActivity();
            form.version = indicator.getVersion();
            form.outputType = indicator.getOutputType();
            form.scoringStrategy = indicator.getScoringStrategy();
            form.yearRange = indicator.getYearRange();
            form.scoreYearRange = indicator.getScoreYearRange();
            // null (the "All" selector) renders as "ALL" in the dropdown; round-trips
            // back through Selector.of("ALL") == All on save.
            form.selector = indicator.getSelector() == null ? "ALL" : indicator.getSelector();
            return form;
        }

        Indicator toIndicator() {
            Indicator indicator = new Indicator();
            indicator.setId(id);
            indicator.setName(name);
            indicator.setFormula(formula);
            indicator.setDomain(domain);
            indicator.setActivity(activity);
            indicator.setVersion(version);
            indicator.setKind(IndicatorKind.of(outputType, scoringStrategy));
            indicator.setYearRangeSpec(YearRangeSpec.parse(yearRange));
            indicator.setScoreYearRangeSpec(ScoreYearRangeSpec.parse(scoreYearRange));
            indicator.setSelectorSpec(Selector.of(selector));
            return indicator;
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
    public String editDomain(@PathVariable String id) {
        return "redirect:/admin/domains";
    }

    @GetMapping("/domains/{id}/edit-data")
    @ResponseBody
    public ResponseEntity<Domain> getDomainEditData(@PathVariable String id) {
        return adminCatalogFacade.findDomainById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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

    @PostMapping("/domains/delete/{id}")
    public String deleteDomain(@PathVariable String id) {
        adminCatalogFacade.deleteDomain(id);
        return "redirect:/admin/domains";
    }

    @PostMapping("/institutions/delete/{id}")
    public String deleteInstitution(@PathVariable String id) {
        adminCatalogFacade.deleteInstitution(id);
        return "redirect:/admin/institutions";
    }

    // -------- Org Divisions --------

    @GetMapping("/divisions")
    public String showDivisionsPage(Model model) {
        List<OrgDivision> divisions = adminCatalogFacade.listOrgDivisions();
        java.util.LinkedHashSet<String> headEmails = new java.util.LinkedHashSet<>();
        for (OrgDivision d : divisions) {
            if (d.getHeadUserIds() != null) headEmails.addAll(d.getHeadUserIds());
        }
        model.addAttribute("divisions", divisions);
        model.addAttribute("institutions", adminCatalogFacade.listInstitutions());
        model.addAttribute("divisionTypes", DivisionType.values());
        model.addAttribute("division", new OrgDivision());
        model.addAttribute("allResearchers", userService.findUsersWithResearcherProfile());
        model.addAttribute("headLabels", userService.findDisplayLabels(headEmails));
        return "admin/divisions";
    }

    @PostMapping("/divisions/create")
    public String createDivision(@ModelAttribute OrgDivision division, RedirectAttributes redirectAttributes) {
        try {
            adminCatalogFacade.saveOrgDivision(division);
            redirectAttributes.addFlashAttribute("successMessage", "Division created successfully.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/divisions";
    }

    @PostMapping("/divisions/update")
    public String updateDivision(@ModelAttribute OrgDivision division, RedirectAttributes redirectAttributes) {
        try {
            adminCatalogFacade.saveOrgDivision(division);
            redirectAttributes.addFlashAttribute("successMessage", "Division updated successfully.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/divisions";
    }

    @GetMapping("/divisions/{id}/edit-data")
    @ResponseBody
    public ResponseEntity<OrgDivision> getDivisionEditData(@PathVariable String id) {
        return adminCatalogFacade.findOrgDivisionById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/divisions/delete/{id}")
    public String deleteDivision(@PathVariable String id, RedirectAttributes redirectAttributes) {
        try {
            adminCatalogFacade.deleteOrgDivision(id);
            redirectAttributes.addFlashAttribute("successMessage", "Division deleted.");
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/divisions";
    }

    @PostMapping("/divisions/import")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
    public String importStaff(@RequestParam("file") MultipartFile file,
                              @RequestParam("institutionId") String institutionId,
                              RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a CSV file to upload.");
            return "redirect:/admin/divisions";
        }
        if (institutionId == null || institutionId.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Target institution is required.");
            return "redirect:/admin/divisions";
        }
        if (file.getSize() > staffImportMaxBytes) {
            redirectAttributes.addFlashAttribute("errorMessage", "CSV file is too large.");
            return "redirect:/admin/divisions";
        }
        if (!hasCsvExtension(file.getOriginalFilename())) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only .csv files are allowed.");
            return "redirect:/admin/divisions";
        }
        if (!isAllowedCsvContentType(file.getContentType())) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unsupported CSV content type.");
            return "redirect:/admin/divisions";
        }
        try {
            StaffImportService.StaffImportResult result = staffImportService.importStaffFromCsv(file, institutionId);
            String attribute = result.skipped.isEmpty() ? "successMessage" : "errorMessage";
            redirectAttributes.addFlashAttribute(attribute, result.toSummaryMessage());
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "An error occurred while importing staff: " + e.getMessage());
            log.error("Staff import failed: fileName={}, size={}", file.getOriginalFilename(), file.getSize(), e);
        }
        return "redirect:/admin/divisions";
    }

    private boolean hasCsvExtension(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private boolean isAllowedCsvContentType(String contentType) {
        if (contentType == null) return false;
        return Arrays.stream(staffImportAllowedContentTypes.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .anyMatch(allowed -> allowed.equals(contentType.toLowerCase(Locale.ROOT)));
    }

    // -------- Departments --------

    @GetMapping("/departments")
    public String showDepartmentsPage(Model model) {
        List<Department> departments = adminCatalogFacade.listDepartments();
        java.util.LinkedHashSet<String> headEmails = new java.util.LinkedHashSet<>();
        for (Department d : departments) {
            if (d.getHeadUserIds() != null) headEmails.addAll(d.getHeadUserIds());
        }
        model.addAttribute("departments", departments);
        model.addAttribute("divisions", adminCatalogFacade.listOrgDivisions());
        model.addAttribute("department", new Department());
        model.addAttribute("allResearchers", userService.findUsersWithResearcherProfile());
        model.addAttribute("headLabels", userService.findDisplayLabels(headEmails));
        return "admin/departments";
    }

    @PostMapping("/departments/create")
    public String createDepartment(@ModelAttribute Department department, RedirectAttributes redirectAttributes) {
        try {
            adminCatalogFacade.saveDepartment(department);
            redirectAttributes.addFlashAttribute("successMessage", "Department created successfully.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/departments";
    }

    @PostMapping("/departments/update")
    public String updateDepartment(@ModelAttribute Department department, RedirectAttributes redirectAttributes) {
        try {
            adminCatalogFacade.saveDepartment(department);
            redirectAttributes.addFlashAttribute("successMessage", "Department updated successfully.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/departments";
    }

    @GetMapping("/departments/{id}/edit-data")
    @ResponseBody
    public ResponseEntity<Department> getDepartmentEditData(@PathVariable String id) {
        return adminCatalogFacade.findDepartmentById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/departments/delete/{id}")
    public String deleteDepartment(@PathVariable String id, RedirectAttributes redirectAttributes) {
        adminCatalogFacade.deleteDepartment(id);
        redirectAttributes.addFlashAttribute("successMessage", "Department deleted.");
        return "redirect:/admin/departments";
    }

    @GetMapping("/scholardex/forums")
    public String showScholardexForumsPage() {
        return "admin/scholardex-forums";
    }

    @GetMapping("/scholardex/forums/edit/{id}")
    public String editScholardexForumPage(@PathVariable String id) {
        return "redirect:/admin/scholardex/forums";
    }

    @GetMapping("/scholardex/forums/{id}/edit-data")
    @ResponseBody
    public ResponseEntity<ScholardexForumView> getScholardexForumEditData(@PathVariable String id) {
        return adminCatalogFacade.findScopusVenueById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/scholardex/forums/edit/{id}")
    public String updateScholardexForum(@PathVariable String id, @ModelAttribute("forum") ScholardexForumView forum, RedirectAttributes redirectAttributes) {
        if (forum.getId() == null || forum.getId().isBlank()) {
            forum.setId(id);
        }
        adminCatalogFacade.saveScopusVenue(forum);
        redirectAttributes.addFlashAttribute("message", "Forum updated successfully!");
        return "redirect:/admin/scholardex/forums";
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
    public String updateScholardexAuthor(@ModelAttribute("author") ScholardexAuthorView author, RedirectAttributes redirectAttributes) {
        return "redirect:/admin/scholardex/authors";
    }

    @GetMapping("/scholardex/affiliations")
    public String showScholardexAffiliationsPage() {
        return "admin/scholardex-affiliations";
    }

    @GetMapping("/scholardex/affiliations/edit/{id}")
    public String editScholardexAffiliationsPage(@PathVariable String id) {
        return "redirect:/admin/scholardex/affiliations";
    }

    @GetMapping("/scholardex/affiliations/{id}/edit-data")
    @ResponseBody
    public ResponseEntity<ScholardexAffiliationView> getScholardexAffiliationEditData(@PathVariable String id) {
        return adminCatalogFacade.findScopusAffiliationById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/scholardex/affiliations/edit/{id}")
    public String updateScholardexAffiliations(@ModelAttribute("affiliation") ScholardexAffiliationView affiliation, RedirectAttributes redirectAttributes, @PathVariable String id) {
        if (affiliation.getAfid() == null || affiliation.getAfid().isBlank()) {
            affiliation.setAfid(id);
        }
        adminCatalogFacade.saveScopusAffiliation(affiliation);
        redirectAttributes.addFlashAttribute("message", "Affiliation updated successfully!");
        return "redirect:/admin/scholardex/affiliations";
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

    /** JSON endpoint used by the inline edit modal — returns updated row data for client-side re-render. */
    @PostMapping(value = "/users/{email}/edit", produces = "application/json")
    @ResponseBody
    public ResponseEntity<AdminUserEditResponse> editUser(
            @PathVariable String email,
            @RequestBody AdminUserEditRequest request) {
        if (!userService.areValidRoleNames(request.roles())) {
            return ResponseEntity.badRequest().build();
        }
        userService.updateUserRoles(email, request.roles());

        if (request.profile() != null) {
            User.ResearcherProfile profile = new User.ResearcherProfile();
            profile.setFirstName(request.profile().firstName());
            profile.setLastName(request.profile().lastName());
            profile.setScholarId(request.profile().scholarId());
            profile.setScopusId(request.profile().scopusIds() != null ? request.profile().scopusIds() : List.of());
            profile.setWosId(request.profile().wosIds() != null ? request.profile().wosIds() : List.of());
            if (request.profile().position() != null && !request.profile().position().isBlank()) {
                try {
                    profile.setPosition(ro.uvt.pokedex.core.model.reporting.Position.valueOf(request.profile().position()));
                } catch (IllegalArgumentException ignored) {}
            }
            userService.saveResearcherProfile(email, profile);
        }

        User updated = userService.getUserByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email));
        return ResponseEntity.ok(AdminUserEditResponse.from(updated));
    }

    record AdminUserEditProfileRequest(
            String firstName,
            String lastName,
            String scholarId,
            List<String> scopusIds,
            List<String> wosIds,
            String position
    ) {}

    record AdminUserEditRequest(
            List<String> roles,
            AdminUserEditProfileRequest profile
    ) {}

    record AdminUserEditResponse(
            String email,
            List<String> roles,
            boolean locked,
            String name,
            String scholarId,
            List<String> scopusIds,
            List<String> wosIds,
            String position,
            boolean hasProfile
    ) {
        static AdminUserEditResponse from(User user) {
            User.ResearcherProfile p = user.getResearcherProfile();
            return new AdminUserEditResponse(
                    user.getEmail(),
                    user.getRoles().stream().map(Enum::name).toList(),
                    user.isLocked(),
                    p != null ? p.getName() : null,
                    p != null ? p.getScholarId() : null,
                    p != null ? p.getScopusId() : List.of(),
                    p != null ? p.getWosId() : List.of(),
                    p != null && p.getPosition() != null ? p.getPosition().name() : null,
                    p != null
            );
        }
    }

}
