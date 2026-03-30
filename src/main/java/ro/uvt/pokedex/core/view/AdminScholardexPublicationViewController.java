package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAdminReadPort;
import ro.uvt.pokedex.core.service.application.model.ScholardexCitationsView;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationSearchView;

import java.util.Optional;

@Controller
@RequestMapping("/admin/scholardex/publications")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url")
public class AdminScholardexPublicationViewController {

    private final PostgresScholardexAdminReadPort postgresScholardexAdminReadPort;

    @GetMapping("/search")
    public String searchScholardexPublications(
            @RequestParam String authorName,
            @RequestParam String paperTitle,
            Model model
    ) {
        ScholardexPublicationSearchView viewModel = postgresScholardexAdminReadPort.buildPublicationSearchView(paperTitle);
        model.addAttribute("authorMap", viewModel.authorMap());
        model.addAttribute("publications", viewModel.publications());
        return "admin/scholardex-publications-search";
    }

    @GetMapping("/citations")
    public String showScholardexPublicationCitationsPage(Model model, @RequestParam("id") String id) {
        Optional<ScholardexCitationsView> viewModel = postgresScholardexAdminReadPort.buildPublicationCitationsView(id);
        viewModel.ifPresent(vm -> {
            model.addAttribute("citations", vm.citations());
            model.addAttribute("publication", vm.publication());
            model.addAttribute("forum", vm.publicationForum());
            model.addAttribute("authorMap", vm.authorMap());
            model.addAttribute("forumMap", vm.forumMap());
        });
        return "admin/scholardex-citations";
    }
}
