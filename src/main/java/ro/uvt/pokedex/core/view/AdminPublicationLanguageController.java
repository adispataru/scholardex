package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillJob;
import ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillService;

/**
 * The data-after-code step for the language and place of publication (Comisia 25, coefficient m): deploy
 * the commit that reads them, then fill in the works that were in the platform before it. Own controller on
 * purpose — adding a constructor arg to an existing admin controller takes its whole {@code @WebMvcTest}
 * slice down. Reached only by platform admins: everything under {@code /admin} is.
 *
 * <p>The buttons are on the admin initialization page. Scores follow at the next refresh of a report.</p>
 */
@Controller
@RequestMapping("/admin/openalex/language")
@RequiredArgsConstructor
public class AdminPublicationLanguageController {

    private static final String BACK = "redirect:/admin/initialization";

    private final OpenAlexLanguageBackfillService backfillService;
    private final OpenAlexLanguageBackfillJob backfillJob;

    /** One bounded pass, answered when it is done. */
    @PostMapping("/backfill")
    @ResponseBody
    public OpenAlexLanguageBackfillService.Result backfill(
            @RequestParam(name = "limit", defaultValue = "500") int limit) {
        return backfillService.backfill(limit);
    }

    /** Starts the background job that runs passes until nothing is left. */
    @PostMapping("/backfill/start")
    public String start(@RequestParam(name = "limit", defaultValue = "500") int limit,
                        @RequestParam(name = "maxPasses", defaultValue = "200") int maxPasses,
                        RedirectAttributes redirectAttributes) {
        boolean started = backfillJob.start(limit, maxPasses);
        redirectAttributes.addFlashAttribute("successMessage", started
                ? "Publication language backfill started. Use \"Show status\" to follow it."
                : "Publication language backfill is already running. " + describe(backfillJob.status()));
        return BACK;
    }

    @PostMapping("/backfill/showStatus")
    public String showStatus(RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("successMessage",
                "Publication language backfill: " + describe(backfillJob.status()));
        return BACK;
    }

    @GetMapping("/backfill/status")
    @ResponseBody
    public OpenAlexLanguageBackfillJob.Status status() {
        return backfillJob.status();
    }

    private static String describe(OpenAlexLanguageBackfillJob.Status status) {
        String text = status.state() + ", passes " + status.passes() + ", works " + status.works()
                + " (language found " + status.withLanguage() + ", not given by OpenAlex " + status.withoutLanguage()
                + "), venues asked " + status.venuesAsked() + " (with a country " + status.venuesWithCountry() + ")";
        if (status.error() != null) {
            text += ", error: " + status.error();
        }
        return text + ".";
    }
}
