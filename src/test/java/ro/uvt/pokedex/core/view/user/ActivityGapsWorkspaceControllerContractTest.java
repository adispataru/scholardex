package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.ActivityGapsFacade;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H142 slice 7 — what each record lacks to count, as the researcher reads it. */
@WebMvcTest(ActivityGapsWorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class ActivityGapsWorkspaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivityGapsFacade gaps;

    @Test
    void theResearcherReadsWhatEachRecordLacksInTheirLanguage() throws Exception {
        User user = new User();
        user.setEmail("ion@e-uvt.ro");
        when(gaps.gapsFor("ion@e-uvt.ro")).thenReturn(Map.of(
                "a1", List.of(new ActivityGapsFacade.RecordGap(ActivityGapsFacade.Gap.ROLE, null)),
                "a2", List.of(new ActivityGapsFacade.RecordGap(ActivityGapsFacade.Gap.JOURNAL_UNLISTED, "2734-6897"))));

        mockMvc.perform(get("/user/workspace/activities/gaps")
                        .with(authenticated(user))
                        .cookie(new jakarta.servlet.http.Cookie("SCHOLARDEX_LANG", "ro")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.a1[0]").value(containsString("Rolul lipsește")))
                .andExpect(jsonPath("$.a2[0]").value(containsString("2734-6897")))
                .andExpect(jsonPath("$.a2[0]").value(containsString("cereți încadrarea")));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticated(User user) {
        return request -> {
            var authentication = new org.springframework.security.authentication.TestingAuthenticationToken(user, null, "RESEARCHER");
            var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }

    @Test
    void nobodySignedInReadsNothing() throws Exception {
        mockMvc.perform(get("/user/workspace/activities/gaps")).andExpect(status().isUnauthorized());
    }
}
