package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.application.AdminDashboardService;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAdminReadPort;
import ro.uvt.pokedex.core.service.application.model.AdminOperationStatus;
import ro.uvt.pokedex.core.service.application.model.ScholardexCitationsView;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/admin/scholardex/publications")
@RequiredArgsConstructor
public class AdminScholardexPublicationViewController {

    private final PostgresScholardexAdminReadPort postgresScholardexAdminReadPort;
    private final AdminDashboardService adminDashboardService;

    @GetMapping({"", "/"})
    public String showPublicationsCatalog(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String forumId,
            @RequestParam(required = false) String authorId,
            @RequestParam(required = false) String affiliationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "title") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            Model model
    ) {
        AdminDashboardService.PublicationCatalogStats stats = adminDashboardService.buildPublicationCatalogStats();
        model.addAttribute("pubStats", stats);

        PostgresScholardexAdminReadPort.PublicationCatalogPage catalogPage =
                postgresScholardexAdminReadPort.buildPublicationCatalogPage(q, forumId, authorId, affiliationId, page, size, sort, direction);
        model.addAttribute("catalogPage", catalogPage);

        model.addAttribute("q", q != null ? q : "");
        model.addAttribute("forumId", forumId != null ? forumId : "");
        model.addAttribute("authorId", authorId != null ? authorId : "");
        model.addAttribute("affiliationId", affiliationId != null ? affiliationId : "");
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("size", size);

        // Context labels for active cross-link filters
        if (forumId != null && !forumId.isBlank()) {
            ScholardexForumView forum = catalogPage.forumMap().get(forumId);
            if (forum == null && !catalogPage.content().isEmpty()) {
                forum = catalogPage.content().stream()
                        .filter(p -> forumId.equals(p.getForum()))
                        .findFirst()
                        .map(p -> catalogPage.forumMap().get(p.getForum()))
                        .orElse(null);
            }
            model.addAttribute("filterContextLabel", forum != null ? "Forum: " + forum.getPublicationName() : "Forum: " + forumId);
        } else if (authorId != null && !authorId.isBlank()) {
            var author = catalogPage.authorMap().get(authorId);
            model.addAttribute("filterContextLabel", author != null ? "Author: " + author.getName() : "Author: " + authorId);
        } else if (affiliationId != null && !affiliationId.isBlank()) {
            model.addAttribute("filterContextLabel", "Affiliation: " + affiliationId);
        }

        return "admin/scholardex-publications";
    }

    @PostMapping("/bulk/reassign-forum")
    public String bulkReassignForum(
            @RequestParam(value = "publicationId", required = false) List<String> publicationIds,
            @RequestParam(required = false) String newForumId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String forumId,
            @RequestParam(required = false) String authorId,
            @RequestParam(required = false) String affiliationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "title") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            RedirectAttributes redirectAttributes
    ) {
        if (publicationIds != null && !publicationIds.isEmpty() && newForumId != null && !newForumId.isBlank()) {
            int updated = postgresScholardexAdminReadPort.bulkReassignForum(publicationIds, newForumId);
            redirectAttributes.addFlashAttribute("bulkSuccess", "Reassigned forum for " + updated + " publication(s).");
        } else {
            redirectAttributes.addFlashAttribute("bulkError", "No publications selected or no forum specified.");
        }
        StringBuilder redirect = new StringBuilder("redirect:/admin/scholardex/publications?page=" + page + "&size=" + size + "&sort=" + sort + "&direction=" + direction);
        if (q != null && !q.isBlank()) redirect.append("&q=").append(q);
        if (forumId != null && !forumId.isBlank()) redirect.append("&forumId=").append(forumId);
        if (authorId != null && !authorId.isBlank()) redirect.append("&authorId=").append(authorId);
        if (affiliationId != null && !affiliationId.isBlank()) redirect.append("&affiliationId=").append(affiliationId);
        return redirect.toString();
    }

    @GetMapping("/search")
    public String searchScholardexPublications(
            @RequestParam(required = false) String authorName,
            @RequestParam(required = false) String paperTitle,
            @RequestParam(required = false) String forumId,
            @RequestParam(required = false) String authorId
    ) {
        StringBuilder redirect = new StringBuilder("redirect:/admin/scholardex/publications?");
        if (paperTitle != null && !paperTitle.isBlank()) redirect.append("q=").append(paperTitle).append("&");
        if (forumId != null && !forumId.isBlank()) redirect.append("forumId=").append(forumId).append("&");
        if (authorId != null && !authorId.isBlank()) redirect.append("authorId=").append(authorId).append("&");
        return redirect.toString().replaceAll("[&?]$", "");
    }

    @GetMapping("/citations")
    public String showScholardexPublicationCitationsPage(
            Model model,
            @RequestParam("id") String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Optional<ScholardexCitationsView> viewModel = postgresScholardexAdminReadPort.buildPublicationCitationsView(id, page, size);
        viewModel.ifPresent(vm -> {
            model.addAttribute("citations", vm.citations());
            model.addAttribute("publication", vm.publication());
            model.addAttribute("forum", vm.publicationForum());
            model.addAttribute("authorMap", vm.authorMap());
            model.addAttribute("forumMap", vm.forumMap());
            model.addAttribute("citationsPage", vm);
        });
        AdminOperationStatus citationSync = adminDashboardService.buildCitationSyncStatus();
        model.addAttribute("citationSync", citationSync);
        model.addAttribute("pubId", id);
        model.addAttribute("citPage", page);
        model.addAttribute("citSize", size);
        return "admin/scholardex-citations";
    }
}
