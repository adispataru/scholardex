package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.service.application.ArtisticEventExpertsAdminService;
import ro.uvt.pokedex.core.service.application.ArtisticEventSeedService;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** H142 slice 3 — who ranks the artistic events of each domain (platform admins only, by the /admin/** rule). */
@Controller
@RequestMapping("/admin/artistic-events/experts")
@RequiredArgsConstructor
public class AdminArtisticEventExpertsController {

    private static final Logger log = LoggerFactory.getLogger(AdminArtisticEventExpertsController.class);

    private final ArtisticEventExpertsAdminService experts;
    private final ArtisticEventSeedService seed;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("page", experts.page());
        return "admin/artistic-event-experts";
    }

    @PostMapping
    public String save(@RequestParam("domain") String domain,
                       @RequestParam(name = "departmentIds", required = false) List<String> departmentIds,
                       @RequestParam(name = "expertEmails", required = false) String expertEmails,
                       Authentication authentication, RedirectAttributes redirect) {
        experts.save(domain, departmentIds, expertEmails, authentication == null ? null : authentication.getName());
        redirect.addFlashAttribute("savedDomain", domain);
        return "redirect:/admin/artistic-events/experts";
    }

    /** Fills the experts' queue with the events an institutional Anexa 6.1 table names (one domain per upload). */
    @PostMapping("/seed")
    public String seed(@RequestParam("file") MultipartFile file, @RequestParam("domain") String domain,
                       Authentication authentication, RedirectAttributes redirect) {
        if (file == null || file.isEmpty() || domain == null || domain.isBlank()) {
            redirect.addFlashAttribute("seedErrorKey", "artisticEvents.experts.seed.missing");
            return "redirect:/admin/artistic-events/experts#seed";
        }
        try (InputStream in = file.getInputStream()) {
            redirect.addFlashAttribute("seedReport", seed.seedFromAnexa61(in, file.getOriginalFilename(), domain,
                    authentication == null ? null : authentication.getName()));
            redirect.addFlashAttribute("seedDomain", domain);
        } catch (IOException | RuntimeException e) {
            log.warn("Anexa 6.1 {} could not be read: {}", file.getOriginalFilename(), e.toString());
            redirect.addFlashAttribute("seedErrorKey", "artisticEvents.experts.seed.unreadable");
        }
        return "redirect:/admin/artistic-events/experts#seed";
    }
}
