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

    @Test
    void nobodyElseCanStartIt() throws Exception {
        mockMvc.perform(post(URL).with(as(false, UserRole.RESEARCHER)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post(URL).with(as(true, UserRole.RESEARCHER)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post(URL).with(csrf())).andExpect(status().is3xxRedirection());

        verify(backfillService, never()).backfill(anyInt());
    }
}
