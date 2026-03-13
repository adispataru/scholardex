package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.ScholardexForumDetailService;
import ro.uvt.pokedex.core.service.application.ScholardexForumMvcService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.WosCategoryPageService;
import ro.uvt.pokedex.core.service.application.WosRankingDetailsReadService;
import ro.uvt.pokedex.core.controller.dto.ScholardexForumPageResponse;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;

import java.util.List;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class RankingViewController {

    private final AdminCatalogFacade adminCatalogFacade;
    private final UrapRankingFacade urapRankingFacade;
    private final WosRankingDetailsReadService wosRankingDetailsReadService;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ScholardexForumMvcService scholardexForumMvcService;
    private final ScholardexForumDetailService scholardexForumDetailService;
    private final WosCategoryPageService wosCategoryPageService;

    @GetMapping("/scholardex/forums")
    public String showScholardexForumsPage() {
        return "scholardex/forums";
    }

    @GetMapping("/scholardex/forums/data")
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

    @GetMapping("/scholardex/forums/{id}")
    public String showScholardexForumDetailsPage(Model model, @PathVariable String id) {
        Optional<ScholardexForumDetailViewModel> detail = scholardexForumDetailService.findDetail(id);
        if (detail.isEmpty()) {
            return "user/ranking-not-found";
        }
        model.addAttribute("detail", detail.get());
        model.addAttribute("forum", detail.get().forum());
        model.addAttribute("wosRanking", detail.get().wosRanking());
        return "scholardex/forum-detail";
    }

    @RequestMapping("/rankings")
    public String redirectRankingsRootToScholardexForums() {
        return "redirect:/scholardex/forums";
    }

    @GetMapping("/rankings/wos")
    public String showWosRankingsPage() {
        return "redirect:/scholardex/forums?wos=indexed";
    }

    @GetMapping("/rankings/wos/{id}")
    public String showWosRankingDetailsPage(@PathVariable String id) {
        return "redirect:/scholardex/forums/" + id;
    }

    @GetMapping("/rankings/categories")
    public String showWosCategoriesPage() {
        return "rankings/categories";
    }

    @GetMapping("/rankings/categories/{key}")
    public String showWosCategoryDetailsPage(Model model, @PathVariable String key) {
        Optional<ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel> detail = wosCategoryPageService.findCategory(key);
        if (detail.isEmpty()) {
            return "user/ranking-not-found";
        }
        model.addAttribute("categoryDetail", detail.get());
        return "rankings/category-detail";
    }

    @GetMapping({"/rankings/core", "/core"})
    public String showCoreRankingsPage() {
        return "rankings/core";
    }

    @GetMapping({"/rankings/core/{id}", "/core/{id}"})
    public String showCoreRankingDetailsPage(Model model, @PathVariable String id) {
        Optional<CoreConferenceRanking> ranking = adminCatalogFacade.findCoreRankingById(id);
        if (ranking.isPresent()) {
            model.addAttribute("conf", ranking.get());
            return "rankings/core-detail";
        }
        return "redirect:/rankings/core";
    }

    @GetMapping({"/rankings/urap", "/urap"})
    public String showUrapRankingsPage() {
        return "rankings/urap";
    }

    @GetMapping({"/rankings/urap/{id}", "/urap/{id}"})
    public String showUrapRankingDetailsPage(@PathVariable String id, Model model) {
        Optional<URAPUniversityRanking> ranking = urapRankingFacade.findRankingDetails(id);
        if (ranking.isPresent()) {
            model.addAttribute("ranking", ranking.get());
            model.addAttribute("fields", List.of("article", "citation", "totalDocument", "AIT", "CIT", "collaboration"));
            return "rankings/urap-detail";
        }
        return "redirect:/rankings/urap";
    }

    @GetMapping({"/rankings/events", "/events"})
    public String showArtisticEventsPage(Model model) {
        List<ArtisticEvent> all = adminCatalogFacade.listArtisticEvents();
        model.addAttribute("artisticEvents", all);
        return "rankings/events";
    }
}
