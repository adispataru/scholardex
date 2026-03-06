package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.WosRankingDetailsReadService;

import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/rankings")
@RequiredArgsConstructor
public class RankingViewController {

    private final AdminCatalogFacade adminCatalogFacade;
    private final UrapRankingFacade urapRankingFacade;
    private final WosRankingDetailsReadService wosRankingDetailsReadService;

    @GetMapping("/wos")
    public String showWosRankingsPage() {
        return "rankings/wos";
    }

    @GetMapping("/wos/{id}")
    public String showWosRankingDetailsPage(Model model, @PathVariable String id) {
        Optional<WoSRanking> ranking = wosRankingDetailsReadService.findByJournalId(id);
        if (ranking.isPresent()) {
            model.addAttribute("journal", ranking.get());
            return "rankings/wos-detail";
        }
        return "user/ranking-not-found";
    }

    @GetMapping("/core")
    public String showCoreRankingsPage() {
        return "rankings/core";
    }

    @GetMapping("/core/{id}")
    public String showCoreRankingDetailsPage(Model model, @PathVariable String id) {
        Optional<CoreConferenceRanking> ranking = adminCatalogFacade.findCoreRankingById(id);
        if (ranking.isPresent()) {
            model.addAttribute("conf", ranking.get());
            return "rankings/core-detail";
        }
        return "redirect:/rankings/core";
    }

    @GetMapping("/urap")
    public String showUrapRankingsPage() {
        return "rankings/urap";
    }

    @GetMapping("/urap/{id}")
    public String showUrapRankingDetailsPage(@PathVariable String id, Model model) {
        Optional<URAPUniversityRanking> ranking = urapRankingFacade.findRankingDetails(id);
        if (ranking.isPresent()) {
            model.addAttribute("ranking", ranking.get());
            model.addAttribute("fields", List.of("article", "citation", "totalDocument", "AIT", "CIT", "collaboration"));
            return "rankings/urap-detail";
        }
        return "redirect:/rankings/urap";
    }

    @GetMapping("/events")
    public String showArtisticEventsPage(Model model) {
        List<ArtisticEvent> all = adminCatalogFacade.listArtisticEvents();
        model.addAttribute("artisticEvents", all);
        return "rankings/events";
    }
}
