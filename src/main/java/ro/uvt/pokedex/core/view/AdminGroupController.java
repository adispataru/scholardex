package ro.uvt.pokedex.core.view;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.GroupCnfisExportFacade;
import ro.uvt.pokedex.core.service.application.GroupExportFacade;
import ro.uvt.pokedex.core.service.application.GroupManagementFacade;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.application.RequestYearRangeSupport;
import ro.uvt.pokedex.core.service.application.GroupReportFacade;
import ro.uvt.pokedex.core.service.application.OrgUnitReportRefreshService;
import ro.uvt.pokedex.core.service.application.model.BreadcrumbItem;
import ro.uvt.pokedex.core.service.application.model.GroupCnfisZipExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupEditViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupListViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupMemberCnfisWorkbook;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationCsvExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupWorkbookExportResult;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.importing.GroupService;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/admin/groups")
@RequiredArgsConstructor
public class AdminGroupController {
    private static final Logger log = LoggerFactory.getLogger(AdminGroupController.class);

    // V01 closed: controller repository debt removed for AdminGroupController.
    // Remaining H02 debt is cross-layer coupling (V02+).
    private final GroupManagementFacade groupManagementFacade;
    private final GroupReportFacade groupReportFacade;
    private final ro.uvt.pokedex.core.service.application.reporting.OrgUnitPromotionBoardService orgUnitPromotionBoardService;
    private final OrgUnitReportRefreshService orgUnitReportRefreshService;
    private final GroupExportFacade groupExportFacade;
    private final GroupCnfisExportFacade groupCnfisExportFacade;
    private final GroupService groupService;
    @Value("${h07.groups.import.max-bytes:2097152}")
    private long maxImportBytes;
    @Value("${h07.groups.import.allowed-content-types:text/csv,application/vnd.ms-excel}")
    private String allowedContentTypes;

    @GetMapping
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
    public String listGroups(Model model) {
        GroupListViewModel viewModel = groupManagementFacade.buildGroupListView();
        model.addAttribute("groups", viewModel.groups());
        model.addAttribute("allDomains", viewModel.allDomains());
        model.addAttribute("institutions", viewModel.institutions());
        model.addAttribute("departmentOptions", viewModel.departmentOptions());
        model.addAttribute("domainsById", viewModel.domainsById());
        model.addAttribute("departmentsById", viewModel.departmentsById());
        model.addAttribute("memberCountByGroupId", viewModel.memberCountByGroupId());
        model.addAttribute("allResearchers", viewModel.allResearchers());
        model.addAttribute("group", viewModel.group());
        return "admin/groups";
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
    public String createGroup(@ModelAttribute Group group,
                              @RequestParam(value = "userIds", required = false) List<String> userIds,
                              RedirectAttributes redirectAttributes) {
        groupManagementFacade.createGroup(group, userIds);
        redirectAttributes.addFlashAttribute("successMessage", "Group created successfully.");
        return "redirect:/admin/groups";
    }

    @GetMapping("/edit/{id}")
    @PreAuthorize("@groupAccess.canEdit(#id, authentication)")
    public String editGroup(@PathVariable String id, Model model) {
        GroupEditViewModel viewModel = groupManagementFacade.buildGroupEditView(id);
        model.addAttribute("group", viewModel.group());
        model.addAttribute("adminFormObject", viewModel.group());
        model.addAttribute("domains", viewModel.domains());
        model.addAttribute("allDomains", viewModel.domains());
        model.addAttribute("institutions", viewModel.institutions());
        model.addAttribute("departmentOptions", viewModel.departmentOptions());
        model.addAttribute("allResearchers", viewModel.allResearchers());
        model.addAttribute("currentMemberUserIds", viewModel.currentMemberUserIds());
        String label = viewModel.group() == null || viewModel.group().getName() == null
                ? "Edit Group"
                : viewModel.group().getName();
        model.addAttribute("breadcrumbs", List.of(
                new BreadcrumbItem("Groups", "/admin/groups"),
                new BreadcrumbItem(label)
        ));
        return "admin/edit-group";
    }

    @GetMapping("/{id}")
    @PreAuthorize("@groupAccess.canView(#id, authentication)")
    public String viewGroupWorkspace(@PathVariable String id, Model model) {
        Optional<GroupPublicationsViewModel> viewModel = groupReportFacade.buildGroupPublicationsView(id);
        if (viewModel.isEmpty()) {
            return "redirect:/admin/groups";
        }
        GroupPublicationsViewModel vm = viewModel.get();
        model.addAttribute("authorMap", vm.authorMap());
        model.addAttribute("publicationsByYear", vm.publicationsByYear());
        model.addAttribute("publicationsCountByYear", vm.publicationsCountByYear());
        model.addAttribute("venueClassCountByYear", vm.venueClassCountByYear());
        model.addAttribute("individualReports", vm.individualReports());
        model.addAttribute("forumMap", vm.forumMap());
        model.addAttribute("group", vm.group());
        model.addAttribute("publications", vm.publications());
        model.addAttribute("researchers", vm.researchers());
        return "admin/group-workspace";
    }

    @GetMapping("/{id}/publications")
    @PreAuthorize("@groupAccess.canView(#id, authentication)")
    public String seeGroupPublications(@PathVariable String id) {
        return "redirect:/admin/groups/" + id + "#publications";
    }

    @GetMapping("{gid}/reports/view/{id}")
    @PreAuthorize("@groupAccess.canView(#gid, authentication)")
    public String viewIndividualReport(Model model, Authentication authentication,
                                       @PathVariable String gid, @PathVariable String id,
                                       @RequestParam(value = "compareTo", required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant compareTo) {
        Optional<OrgUnitReportViewModel> view = groupReportFacade.buildGroupIndividualReportView(gid, id, compareTo);
        if (view.isEmpty()) {
            return "redirect:/admin/groups";
        }
        model.addAttribute("unitType", "group");
        model.addAttribute("vm", view.get());
        model.addAttribute("backHref", "/admin/groups/" + gid);
        model.addAttribute("promotionsHref", "/admin/groups/" + gid + "/reports/view/" + id + "/promotions");
        return "admin/orgunit-report-view";
    }

    @GetMapping("{gid}/reports/view/{id}/promotions")
    @PreAuthorize("@groupAccess.canView(#gid, authentication)")
    public String promotionBoard(@PathVariable String gid,
                                 @PathVariable String id,
                                 @RequestParam(name = "exclude", required = false) String exclude,
                                 Model model) {
        java.util.Set<Integer> excluded =
                ro.uvt.pokedex.core.view.support.PromotionBoardWebSupport.parseExcludedCriteria(exclude);
        var view = orgUnitPromotionBoardService.build(
                ro.uvt.pokedex.core.service.application.reporting.OrgUnitPromotionBoardService.OrgUnitType.GROUP,
                gid, id, excluded);
        if (view.isEmpty()) return "redirect:/admin/groups/" + gid;
        String baseHref = "/admin/groups/" + gid + "/reports/view/" + id + "/promotions";
        model.addAttribute("vm", view.get());
        model.addAttribute("criterionToggles",
                ro.uvt.pokedex.core.view.support.PromotionBoardWebSupport.buildToggles(view.get().report(), excluded, baseHref));
        model.addAttribute("excludedCount", excluded.size());
        model.addAttribute("resetHref", baseHref);
        model.addAttribute("backHref", "/admin/groups/" + gid + "/reports/view/" + id);
        return "admin/orgunit-promotions";
    }

    @PostMapping("{gid}/reports/view/{id}/refresh")
    @PreAuthorize("@groupAccess.canEdit(#gid, authentication)")
    public String refreshIndividualReport(@PathVariable String gid,
                                          @PathVariable String id,
                                          Authentication authentication,
                                          RedirectAttributes flash) {
        try {
            OrgUnitReportRefreshService.RefreshAllResult result = orgUnitReportRefreshService.refreshAll(
                    OrgUnitReportRefreshEvent.UnitType.GROUP, gid, id,
                    OrgUnitReportRefreshService.Scope.ALL, null,
                    authentication == null ? null : authentication.getName());
            flash.addFlashAttribute("successMessage",
                    "Refreshed " + result.refreshed() + " of " + result.rosterSize() + " member(s)."
                            + (result.failed() > 0 ? " " + result.failed() + " failed — see the server log." : "")
                            + (result.skippedProvisional() > 0
                                    ? " " + result.skippedProvisional() + " provisional run(s) skipped." : ""));
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/groups/" + gid + "/reports/view/" + id;
    }

    @PostMapping("{gid}/reports/view/{id}/score-provisional")
    @PreAuthorize("@groupAccess.canEdit(#gid, authentication)")
    public String scoreProvisionalIndividualReport(@PathVariable String gid,
                                                   @PathVariable String id,
                                                   @RequestParam(required = false) String label,
                                                   Authentication authentication,
                                                   RedirectAttributes flash) {
        try {
            OrgUnitReportRefreshService.ProvisionalScoreResult result =
                    orgUnitReportRefreshService.scoreProvisionalUnlinked(
                            OrgUnitReportRefreshEvent.UnitType.GROUP, gid, id, label,
                            authentication == null ? null : authentication.getName());
            flash.addFlashAttribute("successMessage",
                    AdminOrgUnitReportRefreshController.summarizeProvisional(result));
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/groups/" + gid + "/reports/view/" + id;
    }

    @GetMapping("/{id}/publications/export")
    @ResponseBody
    @PreAuthorize("@groupAccess.canView(#id, authentication)")
    public void exportIndicatorResults(@PathVariable String id, Authentication authentication, HttpServletResponse response) throws IOException {
        Optional<GroupPublicationCsvExportViewModel> viewModel = groupExportFacade.buildGroupPublicationCsvExport(id);
        if (viewModel.isEmpty()) {
            return;
        }
        GroupPublicationCsvExportViewModel vm = viewModel.get();

        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"group_publications.csv\"");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("DOI,Title,Authors,Affiliated Authors,Forum,Year,Volume,Page Range");

            for (ScholardexPublicationView publication : vm.publications()) {
                String doi = publication.getDoi() != null ? publication.getDoi() : "";
                String title = publication.getTitle() != null ? publication.getTitle() : "";
                String authorsNames = publication.getAuthors().stream()
                        .map(vm.authorMap()::get)
                        .filter(Objects::nonNull)
                        .map(ScholardexAuthorView::getName)
                        .collect(Collectors.joining(";"));
                String affiliatedAuthors = publication.getAuthors().stream()
                        .map(vm.authorMap()::get)
                        .filter(a -> vm.affiliatedAuthorIds().contains(a.getId()))
                        .map(ScholardexAuthorView::getName)
                        .collect(Collectors.joining(";"));
                String forumName = vm.forumMap().getOrDefault(publication.getForum(), new ScholardexForumView()).getPublicationName();
                String year = PersistenceYearSupport.extractYearString(publication.getCoverDate(), publication.getId(), log);
                String volume = publication.getVolume() != null ? publication.getVolume() : "";
                if (publication.getIssueIdentifier() != null && !publication.getIssueIdentifier().equals("null")) {
                    volume += "(" + publication.getIssueIdentifier() + ")";
                }
                String pageRange = publication.getPageRange() != null ? publication.getPageRange() : "";

                writer.printf("%s,\"%s\",\"%s\",\"%s\",\"%s\",%s,%s,%s%n", doi, title, authorsNames, affiliatedAuthors, forumName, year, volume, pageRange);
            }
        }
    }

    @GetMapping("/{id}/publications/exportCNFIS2025")
    @ResponseBody
    @PreAuthorize("@groupAccess.canView(#id, authentication)")
    public void createCNFISReport2025(@PathVariable String id,
                                      HttpServletResponse response,
                                      @RequestParam(name = "start", defaultValue = "2021") String startYear,
                                      @RequestParam(name = "end", defaultValue = "2024") String endYear) throws IOException {
        RequestYearRangeSupport.YearRange yearRange;
        try {
            yearRange = RequestYearRangeSupport.parseAndValidate(startYear, endYear);
        } catch (IllegalArgumentException ex) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
            return;
        }

        int start = yearRange.start();
        int end = yearRange.end();
        Optional<GroupWorkbookExportResult> workbook = groupCnfisExportFacade.buildGroupCnfisWorkbookExport(id, start, end);
        if (workbook.isEmpty()) {
            return;
        }
        GroupWorkbookExportResult exportResult = workbook.get();
        response.setContentType(exportResult.contentType());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + exportResult.fileName() + "\"");
        response.getOutputStream().write(exportResult.workbookBytes());
    }

    @GetMapping("/{id}/publications/exportAllReports")
    @ResponseBody
    @PreAuthorize("@groupAccess.canView(#id, authentication)")
    public void exportAllReports(@PathVariable String id, HttpServletResponse response) throws IOException {
        Optional<GroupCnfisZipExportViewModel> zipViewModel = groupCnfisExportFacade.buildGroupCnfisZipExport(id, 2021, 2024);
        if (zipViewModel.isEmpty()) {
            return;
        }

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=group_reports.zip");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            for (GroupMemberCnfisWorkbook workbook : zipViewModel.get().workbooks()) {
                zos.putNextEntry(new ZipEntry(workbook.entryName()));
                try (ByteArrayInputStream bis = new ByteArrayInputStream(workbook.workbookBytes())) {
                    bis.transferTo(zos);
                }
                zos.closeEntry();
            }
        }
    }

    @PostMapping("/update")
    @PreAuthorize("@groupAccess.canEdit(#group.id, authentication)")
    public String updateGroup(@ModelAttribute Group group,
                              @RequestParam(value = "userIds", required = false) List<String> userIds,
                              RedirectAttributes redirectAttributes) {
        groupManagementFacade.updateGroup(group, userIds);
        redirectAttributes.addFlashAttribute("successMessage", "Group updated successfully.");
        return "redirect:/admin/groups";
    }

    @PostMapping("/delete/{id}")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
    public String deleteGroup(@PathVariable String id, RedirectAttributes redirectAttributes) {
        groupManagementFacade.deleteGroup(id);
        redirectAttributes.addFlashAttribute("successMessage", "Group deleted successfully.");
        return "redirect:/admin/groups";
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
    public String importGroups(@RequestParam("file") MultipartFile file,
                               @RequestParam("institutionId") String institutionId,
                               RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a CSV file to upload.");
            return "redirect:/admin/groups";
        }
        if (institutionId == null || institutionId.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Target institution is required.");
            return "redirect:/admin/groups";
        }
        if (file.getSize() > maxImportBytes) {
            redirectAttributes.addFlashAttribute("errorMessage", "CSV file is too large.");
            return "redirect:/admin/groups";
        }
        if (!hasCsvExtension(file.getOriginalFilename())) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only .csv files are allowed.");
            return "redirect:/admin/groups";
        }
        if (!isAllowedContentType(file.getContentType())) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unsupported CSV content type.");
            return "redirect:/admin/groups";
        }

        try {
            groupService.importGroupsFromCsv(file, institutionId);
            redirectAttributes.addFlashAttribute("successMessage", "Groups imported successfully.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "An error occurred while importing the groups: " + e.getMessage());
            log.error("Group import failed: fileName={}, size={}", file.getOriginalFilename(), file.getSize(), e);
        }

        return "redirect:/admin/groups";
    }

    private boolean hasCsvExtension(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private boolean isAllowedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        Set<String> allowed = Arrays.stream(allowedContentTypes.split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return allowed.contains(contentType.toLowerCase(Locale.ROOT));
    }
}
