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
import ro.uvt.pokedex.core.service.application.model.BreadcrumbItem;
import ro.uvt.pokedex.core.service.application.model.FilterFieldDef;
import ro.uvt.pokedex.core.service.application.model.FilterOptionDef;
import ro.uvt.pokedex.core.service.application.model.ScholardexCitationsView;
import ro.uvt.pokedex.core.service.application.model.StatCardDef;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
        model.addAttribute("statCards", buildPublicationStatCards(stats));

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
        model.addAttribute("filterFields", buildFilterFields(q, sort, direction, size));

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
            model.addAttribute("breadcrumbs", List.of(
                    new BreadcrumbItem("Publications", "/admin/scholardex/publications"),
                    new BreadcrumbItem(vm.publication().getTitle())
            ));
        });
        AdminOperationStatus citationSync = adminDashboardService.buildCitationSyncStatus();
        model.addAttribute("citationSync", citationSync);
        model.addAttribute("statCards", buildCitationStatCards(
                viewModel.map(ScholardexCitationsView::totalCitations).orElse(0L),
                citationSync
        ));
        model.addAttribute("pubId", id);
        model.addAttribute("citPage", page);
        model.addAttribute("citSize", size);
        return "admin/scholardex-citations";
    }

    private List<FilterFieldDef> buildFilterFields(String q, String sort, String direction, int size) {
        return List.of(
                new FilterFieldDef("q", "Title search", "text", q == null ? "" : q),
                new FilterFieldDef("sort", "Sort", "select", sort, List.of(
                        new FilterOptionDef("title", "Title"),
                        new FilterOptionDef("year", "Year"),
                        new FilterOptionDef("citations", "Citations")
                )),
                new FilterFieldDef("direction", "Direction", "select", direction, List.of(
                        new FilterOptionDef("asc", "Ascending"),
                        new FilterOptionDef("desc", "Descending")
                )),
                new FilterFieldDef("size", "Page size", "select", String.valueOf(size), List.of(
                        new FilterOptionDef("25", "25"),
                        new FilterOptionDef("50", "50"),
                        new FilterOptionDef("100", "100")
                ))
        );
    }

    private List<StatCardDef> buildPublicationStatCards(AdminDashboardService.PublicationCatalogStats stats) {
        return List.of(
                new StatCardDef("Total Publications", stats.total(), "primary", "All canonical publications in the Scholardex catalog.", "fa-solid fa-book-open"),
                new StatCardDef("Added Last 30 Days", stats.recentlyAdded(), "success", "Publications imported in the last 30 days.", "fa-solid fa-calendar-plus")
        );
    }

    private List<StatCardDef> buildCitationStatCards(long totalCitations, AdminOperationStatus citationSync) {
        return List.of(
                new StatCardDef("Citations", totalCitations, "primary", "Citing publications tracked for this record.", "fa-solid fa-quote-right"),
                new StatCardDef(
                        "Last Citation Sync",
                        citationSync.hasRun() ? DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(citationSync.lastRunAt()) : "—",
                        citationSync.hasRun() && AdminOperationStatus.OUTCOME_SUCCESS.equals(citationSync.outcome()) ? "success" : "warning",
                        citationSync.hasRun() ? "Outcome: " + citationSync.outcome() : "No citation sync has run yet.",
                        "fa-solid fa-rotate"
                )
        );
    }
}
