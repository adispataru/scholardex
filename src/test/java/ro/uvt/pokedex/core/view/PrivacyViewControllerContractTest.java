package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H130 (a) — the privacy notice: public, in both languages, honest about test operation, linked from the footer. */
@WebMvcTest(PrivacyViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class PrivacyViewControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theNoticeRendersInRomanianWithTheTestOperationWarningAndThePendingParagraphs() throws Exception {
        mockMvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Notă de confidențialitate")))
                .andExpect(content().string(containsString("regim de testare")))
                .andExpect(content().string(containsString("[de completat")))
                .andExpect(content().string(containsString("Niciun punctaj individual nu este public")))
                .andExpect(content().string(not(containsString("Privacy notice</h1>"))));
    }

    @Test
    void theNoticeRendersInEnglishWhenAsked() throws Exception {
        mockMvc.perform(get("/privacy").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Privacy notice</h1>")))
                .andExpect(content().string(containsString("[to be completed")))
                .andExpect(content().string(containsString("individual score is public")))
                .andExpect(content().string(not(containsString("Notă de confidențialitate</h1>"))));
    }

    @Test
    void theRouteIsPublicAndTheFooterLinksToIt() throws Exception {
        // the allow-list is a literal in the security config; the footer fragment reaches every page (app shell + landing)
        String security = Files.readString(Path.of("src/main/java/ro/uvt/pokedex/core/config/WebSecurityConfig.java"));
        assertTrue(security.contains("\"/privacy\","), "the notice must be readable without an account");
        String fragments = Files.readString(Path.of("src/main/resources/templates/fragments.html"));
        assertTrue(fragments.contains("<a href=\"/privacy\" th:text=\"#{footer.privacy}\">"), "footer link");
    }
}
