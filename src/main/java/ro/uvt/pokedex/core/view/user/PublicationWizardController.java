package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.PublicationWizardFacade;
import ro.uvt.pokedex.core.service.application.model.WizardPublicationCommand;

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
        java.util.Optional<String> forumId = publicationWizardFacade.resolveForumId(newForum, selectedId);
        if (forumId.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Please select an existing forum or provide a valid new forum.");
            return "redirect:/user/publications/add";
        }
        ra.addAttribute("forumId", forumId.get());
        if (selectedId == null || selectedId.isBlank()) {
            ra.addAttribute("wizardForumPublicationName", newForum.getPublicationName());
            ra.addAttribute("wizardForumIssn", newForum.getIssn());
            ra.addAttribute("wizardForumEIssn", newForum.getEIssn());
            ra.addAttribute("wizardForumIsbn", newForum.getIsbn());
            ra.addAttribute("wizardForumAggregationType", newForum.getAggregationType());
            ra.addAttribute("wizardForumPublisher", newForum.getPublisher());
        }
        return "redirect:/user/publications/add/step2";
    }

    @GetMapping("/step2")
    public String showStep2(@RequestParam("forumId") String forumId,
                            @RequestParam(value = "wizardForumPublicationName", required = false) String wizardForumPublicationName,
                            @RequestParam(value = "wizardForumIssn", required = false) String wizardForumIssn,
                            @RequestParam(value = "wizardForumEIssn", required = false) String wizardForumEIssn,
                            @RequestParam(value = "wizardForumIsbn", required = false) String wizardForumIsbn,
                            @RequestParam(value = "wizardForumAggregationType", required = false) String wizardForumAggregationType,
                            @RequestParam(value = "wizardForumPublisher", required = false) String wizardForumPublisher,
                            Model model) {
        model.addAttribute("forumId", forumId);
        model.addAttribute("wizardForumPublicationName", wizardForumPublicationName);
        model.addAttribute("wizardForumIssn", wizardForumIssn);
        model.addAttribute("wizardForumEIssn", wizardForumEIssn);
        model.addAttribute("wizardForumIsbn", wizardForumIsbn);
        model.addAttribute("wizardForumAggregationType", wizardForumAggregationType);
        model.addAttribute("wizardForumPublisher", wizardForumPublisher);
        String afid = "60000434";
        List<Author> authors = publicationWizardFacade.findAuthorsForAffiliation(afid);
        model.addAttribute("allAuthors", authors);

        return "user/publications-add-step2";
    }

    @PostMapping("/step2")
    public String processStep2(@RequestParam("forumId") String forumId,
                               @RequestParam("authorIds") String authorIds,
                               @RequestParam(value = "wizardForumPublicationName", required = false) String wizardForumPublicationName,
                               @RequestParam(value = "wizardForumIssn", required = false) String wizardForumIssn,
                               @RequestParam(value = "wizardForumEIssn", required = false) String wizardForumEIssn,
                               @RequestParam(value = "wizardForumIsbn", required = false) String wizardForumIsbn,
                               @RequestParam(value = "wizardForumAggregationType", required = false) String wizardForumAggregationType,
                               @RequestParam(value = "wizardForumPublisher", required = false) String wizardForumPublisher,
                               RedirectAttributes ra) {
        ra.addAttribute("forumId", forumId);
        ra.addAttribute("authors", authorIds);
        ra.addAttribute("wizardForumPublicationName", wizardForumPublicationName);
        ra.addAttribute("wizardForumIssn", wizardForumIssn);
        ra.addAttribute("wizardForumEIssn", wizardForumEIssn);
        ra.addAttribute("wizardForumIsbn", wizardForumIsbn);
        ra.addAttribute("wizardForumAggregationType", wizardForumAggregationType);
        ra.addAttribute("wizardForumPublisher", wizardForumPublisher);
        return "redirect:/user/publications/add/step3";
    }

    @GetMapping("/step3")
    public String showStep4(@RequestParam("forumId") String forumId,
                            @RequestParam("authors") String authors,
                            @RequestParam(value = "wizardForumPublicationName", required = false) String wizardForumPublicationName,
                            @RequestParam(value = "wizardForumIssn", required = false) String wizardForumIssn,
                            @RequestParam(value = "wizardForumEIssn", required = false) String wizardForumEIssn,
                            @RequestParam(value = "wizardForumIsbn", required = false) String wizardForumIsbn,
                            @RequestParam(value = "wizardForumAggregationType", required = false) String wizardForumAggregationType,
                            @RequestParam(value = "wizardForumPublisher", required = false) String wizardForumPublisher,
                            Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        Forum wizardForumDraft = new Forum();
        wizardForumDraft.setPublicationName(wizardForumPublicationName);
        wizardForumDraft.setIssn(wizardForumIssn);
        wizardForumDraft.setEIssn(wizardForumEIssn);
        wizardForumDraft.setIsbn(wizardForumIsbn);
        wizardForumDraft.setAggregationType(wizardForumAggregationType);
        wizardForumDraft.setPublisher(wizardForumPublisher);

        WizardPublicationCommand pub = publicationWizardFacade.buildPublicationDraft(
                forumId,
                authors,
                currentUser.getResearcherId() != null ? currentUser.getResearcherId() : currentUser.getEmail(),
                wizardForumDraft
        );
        model.addAttribute("publication", pub);
        return "user/publications-add-step3";
    }

    @PostMapping("/step3")
    public String processStep4(@ModelAttribute("publication") WizardPublicationCommand publication,
                               Authentication authentication,
                               RedirectAttributes redirectAttributes) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }
        PublicationWizardFacade.SubmissionResult result = publicationWizardFacade.submitPublication(publication, currentUser);
        if (result.imported()) {
            redirectAttributes.addFlashAttribute("successMessage", "Publication submitted and materialized successfully.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "Publication was already submitted; canonical views were refreshed.");
        }
        return "redirect:/user/publications";
    }
}
