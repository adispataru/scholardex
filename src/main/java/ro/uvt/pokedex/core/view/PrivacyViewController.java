package ro.uvt.pokedex.core.view;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * H130 (a) — the privacy notice: a public page, linked from every footer, that says what personal data the
 * platform processes, where it comes from, who sees it and what a researcher can do about it. The legal
 * basis, the retention period and the controller's contact are the university's to state; until the platform
 * leaves test operation the page says so and marks those paragraphs as pending the data protection officer.
 */
@Controller
@RequestMapping("/privacy")
public class PrivacyViewController {

    @GetMapping
    public String show() {
        return "privacy";
    }
}
