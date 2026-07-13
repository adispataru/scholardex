package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.notification.DirectedNotification.NudgeKind;
import ro.uvt.pokedex.core.service.application.NudgeService;
import ro.uvt.pokedex.core.service.security.ResearcherAccessService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupervisorNudgeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class SupervisorNudgeControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NudgeService nudgeService;
    @MockitoBean(name = "researcherAccess")
    private ResearcherAccessService researcherAccess;

    @Test
    void nudgeForwardsToServiceAndRedirectsToASafeReturnUrl() throws Exception {
        mockMvc.perform(post("/supervisor/nudge")
                        .param("recipientEmail", "bob@uvt.ro")
                        .param("kind", "ONBOARD")
                        .param("note", "please finish setup")
                        .param("returnUrl", "/supervisor/departments/dept-cs/members")
                        .with(user("ana@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/supervisor/departments/dept-cs/members"));
        // Sender = authentication.getName(); @WebMvcTest with addFilters=false can't populate it, so
        // accept any principal — recipient/kind/note are the assertions that matter.
        verify(nudgeService).nudge(any(), eq("bob@uvt.ro"), eq(NudgeKind.ONBOARD), eq("please finish setup"));
    }

    @Test
    void anOffSiteReturnUrlIsIgnoredInFavorOfTheCockpit() throws Exception {
        mockMvc.perform(post("/supervisor/nudge")
                        .param("recipientEmail", "bob@uvt.ro")
                        .param("kind", "REFRESH_REPORT")
                        .param("returnUrl", "https://evil.example.com")
                        .with(user("ana@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/supervisor"));
    }
}
