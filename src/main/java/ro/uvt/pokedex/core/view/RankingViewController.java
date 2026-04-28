package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.controller.dto.ArtisticEventTableItemResponse;
import ro.uvt.pokedex.core.controller.dto.ArtisticEventTablePageResponse;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.ScholardexForumDetailService;
import ro.uvt.pokedex.core.service.application.ScholardexForumMvcService;
import ro.uvt.pokedex.core.service.application.ScholardexPublicationMvcService;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.WosCategoryPageService;
import ro.uvt.pokedex.core.controller.dto.PublicationTablePageResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexForumTablePageResponse;
import ro.uvt.pokedex.core.service.application.model.BreadcrumbItem;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class RankingViewController {

    private final AdminCatalogFacade adminCatalogFacade;
    private final UrapRankingFacade urapRankingFacade;
    private final ScholardexForumMvcService scholardexForumMvcService;
    private final ScholardexForumDetailService scholardexForumDetailService;
    private final WosCategoryPageService wosCategoryPageService;
    private final ScholardexPublicationMvcService scholardexPublicationMvcService;

    @GetMapping("/")
    public String showLandingPage() {
        return "landing";
    }

    @GetMapping("/publications")
    public String showPublicationsPage() {
        return "publications/list";
    }

    @GetMapping("/publications/data")
    @ResponseBody
    public PublicationTablePageResponse listPublicationsData(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "title") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String q
    ) {
        return scholardexPublicationMvcService.search(page, size, sort, direction, q);
    }

    @GetMapping("/publications/{id}")
    public String showPublicationDetailsPage(Model model, @PathVariable String id) {
        Optional<ScholardexPublicationDetailViewModel> detail = scholardexPublicationMvcService.findDetail(id);
        if (detail.isEmpty()) {
            return "shared/not-found";
        }
        model.addAttribute("detail", detail.get());
        model.addAttribute("publication", detail.get().publication());
        model.addAttribute("breadcrumbs", List.of(
                new BreadcrumbItem("Publications", "/publications"),
                new BreadcrumbItem(detail.get().publication().getTitle())
        ));
        return "publications/detail";
    }

    @GetMapping("/forums")
    public String showScholardexForumsPage() {
        return "forums/list";
    }

    @GetMapping("/forums/data")
    @ResponseBody
    public ScholardexForumTablePageResponse listScholardexForumsData(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "publicationName") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String wos
    ) {
        return scholardexForumMvcService.search(page, size, sort, direction, q, wos);
    }

    @GetMapping("/forums/{id}")
    public String showScholardexForumDetailsPage(Model model, @PathVariable String id) {
        Optional<ScholardexForumDetailViewModel> detail = scholardexForumDetailService.findDetail(id);
        if (detail.isEmpty()) {
            return "shared/not-found";
        }
        model.addAttribute("detail", detail.get());
        model.addAttribute("forum", detail.get().forum());
        model.addAttribute("wosRanking", detail.get().wosRanking());
        model.addAttribute("breadcrumbs", List.of(
                new BreadcrumbItem("Forums", "/forums"),
                new BreadcrumbItem(detail.get().forum().getPublicationName())
        ));
        return "forums/detail";
    }

    @GetMapping("/wos/categories")
    public String showWosCategoriesPage() {
        return "redirect:/rankings#wos";
    }

    @GetMapping("/wos/categories/{key}")
    public String showWosCategoryDetailsPage(Model model, @PathVariable String key) {
        Optional<ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel> detail = wosCategoryPageService.findCategory(key);
        if (detail.isEmpty()) {
            return "shared/not-found";
        }
        model.addAttribute("categoryDetail", detail.get());
        model.addAttribute("breadcrumbs", List.of(
                new BreadcrumbItem("Rankings", "/rankings#wos"),
                new BreadcrumbItem(detail.get().categoryName())
        ));
        return "wos/category-detail";
    }

    @GetMapping("/rankings")
    public String showRankingsHubPage() {
        return "rankings/hub";
    }

    @GetMapping("/core/rankings")
    public String redirectCoreRankingsToHub() {
        return "redirect:/rankings#core";
    }

    @GetMapping("/core/rankings/{id}")
    public String showCoreRankingDetailsPage(Model model, @PathVariable String id) {
        Optional<CoreConferenceRanking> ranking = adminCatalogFacade.findCoreRankingById(id);
        if (ranking.isPresent()) {
            CoreConferenceRanking conf = ranking.get();
            CoreConferenceRanking.YearlyRanking latest = conf.getClosestYear(2023);
            model.addAttribute("conf", conf);
            model.addAttribute("latestRankLabel", formatCoreRank(latest));
            model.addAttribute("latestRankAccent", coreRankAccent(latest));
            model.addAttribute("breadcrumbs", List.of(
                    new BreadcrumbItem("Rankings", "/rankings#core"),
                    new BreadcrumbItem(conf.getAcronym())
            ));
            return "core/ranking-detail";
        }
        return "redirect:/rankings#core";
    }

    private static String formatCoreRank(CoreConferenceRanking.YearlyRanking yr) {
        if (yr == null || yr.getRank() == null) return "—";
        return switch (yr.getRank()) {
            case A_STAR -> "A*";
            case NON_RANK -> "Unranked";
            case REMOVED -> "Removed";
            default -> yr.getRank().name();
        };
    }

    private static String coreRankAccent(CoreConferenceRanking.YearlyRanking yr) {
        if (yr == null || yr.getRank() == null) return "neutral";
        return switch (yr.getRank()) {
            case A_STAR -> "success";
            case A -> "primary";
            case B -> "warning";
            default -> "neutral";
        };
    }

    @GetMapping("/universities")
    public String redirectUniversitiesToHub() {
        return "redirect:/rankings#universities";
    }

    @GetMapping("/universities/{id}")
    public String showUrapRankingDetailsPage(@PathVariable String id, Model model) {
        Optional<URAPUniversityRanking> ranking = urapRankingFacade.findRankingDetails(id);
        if (ranking.isPresent()) {
            model.addAttribute("ranking", ranking.get());
            model.addAttribute("fields", List.of("article", "citation", "totalDocument", "AIT", "CIT", "collaboration"));
            model.addAttribute("bestRank", bestRank(ranking.get()));
            model.addAttribute("latestYear", latestYear(ranking.get()));
            model.addAttribute("latestScore", latestTotalScore(ranking.get()));
            model.addAttribute("breadcrumbs", List.of(
                    new BreadcrumbItem("Rankings", "/rankings#universities"),
                    new BreadcrumbItem(ranking.get().getName())
            ));
            return "universities/detail";
        }
        return "redirect:/rankings#universities";
    }

    private Integer bestRank(URAPUniversityRanking ranking) {
        return scoreEntries(ranking).stream()
                .map(entry -> entry.getValue().getRank())
                .filter(rank -> rank > 0)
                .min(Integer::compareTo)
                .orElse(null);
    }

    private Integer latestYear(URAPUniversityRanking ranking) {
        return scoreEntries(ranking).stream()
                .map(Map.Entry::getKey)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private Double latestTotalScore(URAPUniversityRanking ranking) {
        return scoreEntries(ranking).stream()
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .map(URAPUniversityRanking.Score::getTotal)
                .orElse(null);
    }

    private List<Map.Entry<Integer, URAPUniversityRanking.Score>> scoreEntries(URAPUniversityRanking ranking) {
        if (ranking.getScores() == null || ranking.getScores().isEmpty()) {
            return List.of();
        }
        return ranking.getScores().entrySet().stream().toList();
    }

    @GetMapping("/events")
    public String redirectEventsToHub() {
        return "redirect:/rankings#events";
    }

    @GetMapping("/events/data")
    @ResponseBody
    public ArtisticEventTablePageResponse listEventsData(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String q
    ) {
        List<ArtisticEvent> all = adminCatalogFacade.listArtisticEvents();

        List<ArtisticEvent> filtered = (q == null || q.isBlank())
                ? all
                : all.stream().filter(e -> e.getName() != null
                        && e.getName().toLowerCase().contains(q.toLowerCase())).toList();

        Comparator<ArtisticEvent> comparator = switch (sort) {
            case "rank" -> Comparator.comparing(e -> e.getRank() == null ? "" : e.getRank().name());
            case "domainId" -> Comparator.comparing(e -> e.getDomainId() == null ? "" : e.getDomainId());
            default -> Comparator.comparing(e -> e.getName() == null ? "" : e.getName());
        };
        if ("desc".equalsIgnoreCase(direction)) comparator = comparator.reversed();

        List<ArtisticEvent> sorted = filtered.stream().sorted(comparator).toList();

        int clampedSize = Math.max(1, Math.min(size, 100));
        int totalItems = sorted.size();
        int totalPages = (int) Math.ceil((double) totalItems / clampedSize);
        int clampedPage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));

        List<ArtisticEventTableItemResponse> items = sorted.stream()
                .skip((long) clampedPage * clampedSize)
                .limit(clampedSize)
                .map(e -> new ArtisticEventTableItemResponse(
                        e.getId(), e.getName(),
                        e.getRank() == null ? null : e.getRank().name(),
                        formatEventRank(e.getRank()),
                        e.getDomainId()))
                .toList();

        return new ArtisticEventTablePageResponse(items, clampedPage, clampedSize, totalItems, totalPages);
    }

    private static String formatEventRank(ArtisticEvent.Rank rank) {
        if (rank == null) return "—";
        return switch (rank) {
            case INTERNATIONAL_TOP -> "1 — International Top";
            case INTERNATIONAL -> "2 — International";
            case NATIONAL -> "3 — National";
        };
    }
}
