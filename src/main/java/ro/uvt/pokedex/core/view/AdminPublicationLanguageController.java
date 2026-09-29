package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillService;

/**
 * The data-after-code step for the language and place of publication (Comisia 25, coefficient m): deploy
 * the commit that reads them, then fill in the works synced before it. Own controller on purpose — adding a
 * constructor arg to an existing admin controller takes its whole {@code @WebMvcTest} slice down. Reached
 * only by platform admins: everything under {@code /admin} is.
 */
@RestController
@RequestMapping("/admin/openalex/language")
@RequiredArgsConstructor
public class AdminPublicationLanguageController {

    private final OpenAlexLanguageBackfillService backfillService;

    /**
     * One bounded pass; repeat until {@code candidates} is 0. Scores follow at the next refresh of a report;
     * a coefficient resolved in the last ten minutes is still remembered until then.
     */
    @PostMapping("/backfill")
    public OpenAlexLanguageBackfillService.Result backfill(
            @RequestParam(name = "limit", defaultValue = "500") int limit) {
        return backfillService.backfill(limit);
    }
}
