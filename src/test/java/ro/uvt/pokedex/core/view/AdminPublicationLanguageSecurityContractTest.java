package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillService;

import java.util.HashSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The backfill calls an outside service and writes; only a platform admin may start it. Real security rules. */
@WebMvcTest(AdminPublicationLanguageController.class)
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class AdminPublicationLanguageSecurityContractTest {

    private static final String URL = "/admin/openalex/language/backfill";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private OpenAlexLanguageBackfillService backfillService;
    @MockitoBean
    private ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillJob backfillJob;

    private static RequestPostProcessor as(boolean head, UserRole... stored) {
        User user = new User();
        user.setEmail("someone@uvt.ro");
        user.setRoles(new HashSet<>(Set.of(stored)));
        user.setSupervisorByPosition(head);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    @Test
    void aPlatformAdminRunsOneBoundedPass() throws Exception {
        when(backfillService.backfill(200)).thenReturn(new OpenAlexLanguageBackfillService.Result(200, 180, 20, 35, 30));

        mockMvc.perform(post(URL).param("limit", "200").with(as(false, UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").value(200))
                .andExpect(jsonPath("$.withLanguage").value(180))
                .andExpect(jsonPath("$.venuesWithCountry").value(30));
    }

    private static ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillJob.Status running() {
        return new ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillJob.Status(
                ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillJob.State.RUNNING,
                3, 1500, 1400, 100, 210, 190, java.time.Instant.parse("2026-09-29T18:00:00Z"), null, null);
    }

    @Test
    void aPlatformAdminStartsTheJobFromTheAdminPage() throws Exception {
        when(backfillJob.start(500, 200)).thenReturn(true);

        mockMvc.perform(post(URL + "/start").with(as(false, UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/initialization"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("successMessage", org.hamcrest.Matchers.containsString("started")));

        verify(backfillJob).start(500, 200);
    }

    @Test
    void aSecondStartSaysThatItIsAlreadyRunning() throws Exception {
        when(backfillJob.start(500, 200)).thenReturn(false);
        when(backfillJob.status()).thenReturn(running());

        mockMvc.perform(post(URL + "/start").with(as(false, UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(redirectedUrl("/admin/initialization"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("successMessage", org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("already running"),
                                org.hamcrest.Matchers.containsString("works 1500"))));
    }

    @Test
    void theStateOfTheJobCanBeReadOnThePageAndAsJson() throws Exception {
        when(backfillJob.status()).thenReturn(running());

        mockMvc.perform(post(URL + "/showStatus").with(as(false, UserRole.PLATFORM_ADMIN)).with(csrf()))
                .andExpect(redirectedUrl("/admin/initialization"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("successMessage", org.hamcrest.Matchers.containsString("RUNNING, passes 3")));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URL + "/status")
                        .with(as(false, UserRole.PLATFORM_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.works").value(1500))
                .andExpect(jsonPath("$.venuesWithCountry").value(190));
    }

    @Test
    void nobodyElseCanStartItOrReadItsState() throws Exception {
        for (String path : new String[]{URL, URL + "/start", URL + "/showStatus"}) {
            mockMvc.perform(post(path).with(as(false, UserRole.RESEARCHER)).with(csrf()))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/custom-error?error=403"));
            mockMvc.perform(post(path).with(as(true, UserRole.RESEARCHER)).with(csrf()))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/custom-error?error=403"));
            mockMvc.perform(post(path).with(csrf())).andExpect(status().is3xxRedirection());
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URL + "/status")
                        .with(as(true, UserRole.RESEARCHER)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/custom-error?error=403"));

        verify(backfillService, never()).backfill(anyInt());
        verify(backfillJob, never()).start(anyInt(), anyInt());
        verify(backfillJob, never()).status();
    }
}
