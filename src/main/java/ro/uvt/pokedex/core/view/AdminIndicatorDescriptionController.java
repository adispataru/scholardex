package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.service.application.IndicatorDescriptionService;

/**
 * H94 — the data-after-code step for indicator descriptions: deploy the commit that carries
 * {@code indicator-descriptions/*.json}, then apply it here. Own controller on purpose — adding a
 * constructor arg to an existing admin controller takes its whole {@code @WebMvcTest} slice down.
 */
@RestController
@RequestMapping("/admin/indicators/descriptions")
@RequiredArgsConstructor
public class AdminIndicatorDescriptionController {

    private final IndicatorDescriptionService indicatorDescriptionService;

    @PostMapping("/apply")
    public IndicatorDescriptionService.ApplyReport apply(
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        return indicatorDescriptionService.apply(dryRun);
    }
}
