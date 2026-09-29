package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import ro.uvt.pokedex.core.service.application.PublicCatalogScope;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.model.BreadcrumbItem;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Controller
@RequiredArgsConstructor
public class AuthorViewController {

    private final UserPublicationFacade userPublicationFacade;
    private final PublicCatalogScope publicCatalogScope;

    @GetMapping("/authors")
    public String showAuthorsPage() {
        return "authors/list";
    }

    @GetMapping("/authors/view/{id}")
    public String showAuthorDetailPage(@PathVariable("id") String authorId, Model model,
                                       Authentication authentication) {
        // H119: a visitor sees the university's own authors, without citation totals and h-index.
        Optional<Set<String>> restriction = publicCatalogScope.restrictionFor(authentication);
        if (restriction.isPresent() && !restriction.get().contains(authorId)) {
            return "shared/not-found";
        }
        boolean showMetrics = restriction.isEmpty();
        Optional<UserPublicationsViewModel> viewModelOpt = userPublicationFacade.buildAuthorPublicationsView(authorId);
        if (viewModelOpt.isEmpty()) {
            return "shared/not-found";
        }
        UserPublicationsViewModel viewModel = viewModelOpt.get();
        String displayName = viewModel.profileAuthor() != null && viewModel.profileAuthor().getName() != null
                ? viewModel.profileAuthor().getName()
                : authorId;

        model.addAttribute("publications", viewModel.publications());
        model.addAttribute("showMetrics", showMetrics);
        model.addAttribute("hIndex", showMetrics ? viewModel.hIndex() : null);
        model.addAttribute("authorMap", viewModel.authorMap());
        model.addAttribute("forumMap", viewModel.forumMap());
        model.addAttribute("numCitations", showMetrics ? viewModel.numCitations() : null);
        model.addAttribute("profileAuthor", viewModel.profileAuthor());
        model.addAttribute("affiliations", viewModel.affiliations());
        model.addAttribute("displayName", displayName);
        model.addAttribute("breadcrumbs", List.of(
                new BreadcrumbItem("Authors", "/authors"),
                new BreadcrumbItem(displayName)
        ));
        return "authors/detail";
    }
}
