package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.scopus.*;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.*;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
@RequestMapping("/user/publications/add")
public class PublicationWizardController {

    private final ScopusForumRepository forumRepo;
    private final ScopusAuthorRepository authorRepo;
    private final ScopusFundingRepository fundingRepo;
    private final ScopusPublicationRepository pubRepo;
    private final ScopusAffiliationRepository scopusAffiliationRepository;



    @GetMapping()
    public String showStep1(Model model) {
        model.addAttribute("forums", forumRepo.findAll());
        model.addAttribute("newForum", new Forum());
        return "user/publications-add-step1";
    }

    @PostMapping("/step1")
    public String processStep1(@ModelAttribute("newForum") Forum newForum,
                               @RequestParam(value="selectedForumId", required=false) String selectedId,
                               RedirectAttributes ra) {
        String forumId = null;
        if(selectedId != null && !selectedId.isEmpty()) {
            Forum existingForum = forumRepo.findById(selectedId).orElse(null);
            if(existingForum != null) {
                forumId = existingForum.getId();
            }
        } else if (newForum.getPublicationName() != null && !newForum.getPublicationName().isEmpty()) {
            forumId = forumRepo.save(newForum).getId();
        }
        ra.addAttribute("forumId", forumId);
        return "redirect:/user/publications/add/step2";
    }

    @GetMapping("/step2")
    public String showStep2(@RequestParam("forumId") String forumId, Model model) {
        model.addAttribute("forumId", forumId);
        String afid = "60000434";
        Optional<Affiliation> byId = scopusAffiliationRepository.findById(afid);
        byId.ifPresent(af -> model.addAttribute("allAuthors", authorRepo.findAllByAffiliationsContaining(af)));

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
        Publication pub = new Publication();
        pub.setForum(forumId);
        pub.setCreator(currentUser.getResearcherId() != null ? currentUser.getResearcherId() : currentUser.getEmail());
        pub.setAuthors(Arrays.asList(authors.split(",")));
        model.addAttribute("publication", pub);
        return "user/publications-add-step3";
    }

    @PostMapping("/step3")
    public String processStep4(@ModelAttribute("publication") Publication publication) {
        pubRepo.save(publication);
        return "redirect:/user/publications";
    }
}