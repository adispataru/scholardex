package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.PublicationWizardFacade;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/user/publications/add")
public class PublicationWizardController {

    private final PublicationWizardFacade publicationWizardFacade;



    @GetMapping()
    public String showStep1(Model model) {
        model.addAttribute("forums", publicationWizardFacade.listForums());
        model.addAttribute("newForum", new Forum());
        return "user/publications-add-step1";
    }

    @PostMapping("/step1")
    public String processStep1(@ModelAttribute("newForum") Forum newForum,
                               @RequestParam(value="selectedForumId", required=false) String selectedId,
                               RedirectAttributes ra) {
        String forumId = publicationWizardFacade.resolveForumId(newForum, selectedId);
        ra.addAttribute("forumId", forumId);
        return "redirect:/user/publications/add/step2";
    }

    @GetMapping("/step2")
    public String showStep2(@RequestParam("forumId") String forumId, Model model) {
        model.addAttribute("forumId", forumId);
        String afid = "60000434";
        List<Author> authors = publicationWizardFacade.findAuthorsForAffiliation(afid);
        model.addAttribute("allAuthors", authors);

        return "user/publications-add-step2";
    }

    @PostMapping("/step2")
    public String processStep2(@RequestParam("forumId") String forumId,
                               @RequestParam("authorIds") String authorIds,
                               RedirectAttributes ra) {
        ra.addAttribute("forumId", forumId);
        ra.addAttribute("authors", authorIds);
        return "redirect:/user/publications/add/step3";
    }

    @GetMapping("/step3")
    public String showStep4(@RequestParam("forumId") String forumId,
                            @RequestParam("authors") String authors,
                            Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }
        Publication pub = publicationWizardFacade.buildPublicationDraft(
                forumId,
                authors,
                currentUser.getResearcherId() != null ? currentUser.getResearcherId() : currentUser.getEmail()
        );
        model.addAttribute("publication", pub);
        return "user/publications-add-step3";
    }

    @PostMapping("/step3")
    public String processStep4(@ModelAttribute("publication") Publication publication) {
        publicationWizardFacade.savePublication(publication);
        return "redirect:/user/publications";
    }
}
