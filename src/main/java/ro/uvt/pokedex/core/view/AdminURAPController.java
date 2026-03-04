package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;

import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/admin/rankings/urap")
@RequiredArgsConstructor
public class AdminURAPController {

    private final UrapRankingFacade urapRankingFacade;

    @GetMapping
    public String getRankings(Model model) {
        List<URAPUniversityRanking> rankings = urapRankingFacade.listRankings();
        model.addAttribute("rankings", rankings);
        return "admin/rankings-urap";
    }

    @GetMapping("/{id}")
    public String getRankingDetails(@PathVariable String id, Model model) {
        Optional<URAPUniversityRanking> ranking = urapRankingFacade.findRankingDetails(id);
        if (ranking.isPresent()) {
            model.addAttribute("ranking", ranking.get());
            model.addAttribute("fields", List.of("article", "citation", "totalDocument", "AIT", "CIT", "collaboration"));
            return "admin/rankings-urap-details";
        }
        return "redirect:/admin/rankings/urap";
    }

}
