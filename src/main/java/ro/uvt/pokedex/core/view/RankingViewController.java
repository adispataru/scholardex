package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.ScholardexForumDetailService;
import ro.uvt.pokedex.core.service.application.ScholardexForumMvcService;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.WosCategoryPageService;
import ro.uvt.pokedex.core.controller.dto.ScholardexForumPageResponse;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;

import java.util.List;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class RankingViewController {

    private final AdminCatalogFacade adminCatalogFacade;
    private final UrapRankingFacade urapRankingFacade;
    private final ScholardexForumMvcService scholardexForumMvcService;
    private final ScholardexForumDetailService scholardexForumDetailService;
    private final WosCategoryPageService wosCategoryPageService;

    @GetMapping("/forums")
    public String showScholardexForumsPage() {
        return "forums/list";
    }

    @GetMapping("/forums/data")
    @ResponseBody
    public ScholardexForumPageResponse listScholardexForumsData(
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
        return "forums/detail";
    }

    @GetMapping("/wos/categories")
    public String showWosCategoriesPage() {
        return "wos/categories";
    }

    @GetMapping("/wos/categories/{key}")
    public String showWosCategoryDetailsPage(Model model, @PathVariable String key) {
        Optional<ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel> detail = wosCategoryPageService.findCategory(key);
        if (detail.isEmpty()) {
            return "shared/not-found";
        }
        model.addAttribute("categoryDetail", detail.get());
        return "wos/category-detail";
    }

    @GetMapping("/core/rankings")
    public String showCoreRankingsPage() {
        return "core/rankings";
    }

    @GetMapping("/core/rankings/{id}")
    public String showCoreRankingDetailsPage(Model model, @PathVariable String id) {
        Optional<CoreConferenceRanking> ranking = adminCatalogFacade.findCoreRankingById(id);
        if (ranking.isPresent()) {
            model.addAttribute("conf", ranking.get());
            return "core/ranking-detail";
        }
        return "redirect:/core/rankings";
    }

    @GetMapping("/universities")
    public String showUrapRankingsPage() {
        return "universities/list";
    }

    @GetMapping("/universities/{id}")
    public String showUrapRankingDetailsPage(@PathVariable String id, Model model) {
        Optional<URAPUniversityRanking> ranking = urapRankingFacade.findRankingDetails(id);
        if (ranking.isPresent()) {
            model.addAttribute("ranking", ranking.get());
            model.addAttribute("fields", List.of("article", "citation", "totalDocument", "AIT", "CIT", "collaboration"));
            return "universities/detail";
        }
        return "redirect:/universities";
    }

    @GetMapping("/events")
    public String showArtisticEventsPage(Model model) {
        List<ArtisticEvent> all = adminCatalogFacade.listArtisticEvents();
        model.addAttribute("artisticEvents", all);
        return "events/list";
    }
}
