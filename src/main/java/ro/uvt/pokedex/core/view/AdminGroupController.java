package ro.uvt.pokedex.core.view;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.GroupCnfisExportFacade;
import ro.uvt.pokedex.core.service.application.GroupExportFacade;
import ro.uvt.pokedex.core.service.application.GroupManagementFacade;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.application.GroupReportFacade;
import ro.uvt.pokedex.core.service.application.model.GroupCnfisZipExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupEditViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupIndividualReportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupListViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupMemberCnfisWorkbook;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationCsvExportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupWorkbookExportResult;
import ro.uvt.pokedex.core.service.importing.GroupService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.Optional;
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
    private final GroupExportFacade groupExportFacade;
    private final GroupCnfisExportFacade groupCnfisExportFacade;
    private final GroupService groupService;

    @GetMapping
    public String listGroups(Model model) {
        GroupListViewModel viewModel = groupManagementFacade.buildGroupListView();
        model.addAttribute("groups", viewModel.groups());
        model.addAttribute("allDomains", viewModel.allDomains());
        model.addAttribute("affiliations", viewModel.affiliations());
        model.addAttribute("allResearchers", viewModel.allResearchers());
        model.addAttribute("group", viewModel.group());
        return "admin/groups";
    }

    @PostMapping("/create")
    public String createGroup(@ModelAttribute Group group, RedirectAttributes redirectAttributes) {
        groupManagementFacade.createGroup(group);
        redirectAttributes.addFlashAttribute("successMessage", "Group created successfully.");
        return "redirect:/admin/groups";
    }

    @GetMapping("/edit/{id}")
    public String editGroup(@PathVariable String id, Model model) {
        GroupEditViewModel viewModel = groupManagementFacade.buildGroupEditView(id);
        model.addAttribute("group", viewModel.group());
        model.addAttribute("domains", viewModel.domains());
        model.addAttribute("affiliations", viewModel.affiliations());
        model.addAttribute("allResearchers", viewModel.allResearchers());
        return "admin/edit-group";
    }

    @GetMapping("/{id}/publications")
    public String seeGroupPublications(@PathVariable String id, Model model) {
        Optional<GroupPublicationsViewModel> viewModel = groupReportFacade.buildGroupPublicationsView(id);
        if (viewModel.isEmpty()) {
            return "redirect:/admin/groups";
        }
        GroupPublicationsViewModel vm = viewModel.get();
        model.addAttribute("authorMap", vm.authorMap());
        model.addAttribute("publicationsByYear", vm.publicationsByYear());
        model.addAttribute("publicationsCountByYear", vm.publicationsCountByYear());
        model.addAttribute("individualReports", vm.individualReports());
        model.addAttribute("forumMap", vm.forumMap());
        model.addAttribute("group", vm.group());
        model.addAttribute("publications", vm.publications());
        return "admin/group-publications";
    }

    @GetMapping("{gid}/reports/view/{id}")
    public String viewIndividualReport(Model model, Authentication authentication, @PathVariable("gid") String gid, @PathVariable("id") String id) {
        GroupIndividualReportViewModel viewModel = groupReportFacade.buildGroupIndividualReportView(gid, id);
        if (viewModel.redirect() != null) {
            return viewModel.redirect();
        }
        viewModel.attributes().forEach(model::addAttribute);
        return "admin/group-individualReport-view";
    }

    @GetMapping("/{id}/publications/export")
    @ResponseBody
    public void exportIndicatorResults(@PathVariable("id") String id, Authentication authentication, HttpServletResponse response) throws IOException {
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

            for (Publication publication : vm.publications()) {
                String doi = publication.getDoi() != null ? publication.getDoi() : "";
                String title = publication.getTitle() != null ? publication.getTitle() : "";
                String authorsNames = publication.getAuthors().stream()
                        .map(vm.authorMap()::get)
                        .filter(Objects::nonNull)
                        .map(Author::getName)
                        .collect(Collectors.joining(";"));
                String affiliatedAuthors = publication.getAuthors().stream()
                        .map(vm.authorMap()::get)
                        .filter(a -> vm.affiliatedAuthorIds().contains(a.getId()))
                        .map(Author::getName)
                        .collect(Collectors.joining(";"));
                String forumName = vm.forumMap().getOrDefault(publication.getForum(), new Forum()).getPublicationName();
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
    public void createCNFISReport2025(@PathVariable("id") String id,
                                      HttpServletResponse response,
                                      @RequestParam(name = "start", defaultValue = "2021") String startYear,
                                      @RequestParam(name = "end", defaultValue = "2024") String endYear) throws IOException {
        int start = Integer.parseInt(startYear);
        int end = Integer.parseInt(endYear);
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
    public void exportAllReports(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
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
    public String updateGroup(@ModelAttribute Group group, RedirectAttributes redirectAttributes) {
        groupManagementFacade.updateGroup(group);
        redirectAttributes.addFlashAttribute("successMessage", "Group updated successfully.");
        return "redirect:/admin/groups";
    }

    @GetMapping("/delete/{id}")
    public String deleteGroup(@PathVariable String id, RedirectAttributes redirectAttributes) {
        groupManagementFacade.deleteGroup(id);
        redirectAttributes.addFlashAttribute("successMessage", "Group deleted successfully.");
        return "redirect:/admin/groups";
    }

    @PostMapping("/import")
    public String importGroups(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a CSV file to upload.");
            return "redirect:/admin/groups";
        }

        try {
            groupService.importGroupsFromCsv(file);
            redirectAttributes.addFlashAttribute("successMessage", "Groups imported successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "An error occurred while importing the groups: " + e.getMessage());
            log.error("Group import failed: fileName={}, size={}", file.getOriginalFilename(), file.getSize(), e);
        }

        return "redirect:/admin/groups";
    }
}
