package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.service.application.PublicationWizardFacade;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicationWizardController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicationWizardControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicationWizardFacade publicationWizardFacade;

    @Test
    void processStep1RedirectsBackWithFlashErrorWhenForumSelectionInvalid() throws Exception {
        when(publicationWizardFacade.resolveForumId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("missing")))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/user/publications/add/step1")
                        .param("selectedForumId", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/publications/add"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void processStep1RedirectsToStep2WhenForumSelectionValid() throws Exception {
        when(publicationWizardFacade.resolveForumId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("f1")))
                .thenReturn(Optional.of("f1"));

        mockMvc.perform(post("/user/publications/add/step1")
                        .param("selectedForumId", "f1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/publications/add/step2?forumId=f1"));
    }
}
