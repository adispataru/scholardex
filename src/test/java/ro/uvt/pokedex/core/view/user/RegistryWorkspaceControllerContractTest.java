package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.controller.EntityRegistryApiController;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.RegistryLookupFacade;
import ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H142 slice 3, H144 — what the researcher reads about the entities a record names, in their language. */
@WebMvcTest({RegistryWorkspaceController.class, EntityRegistryApiController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class RegistryWorkspaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistryLookupFacade lookup;

    @Test
    void theResearcherReadsTheLevelAndWhyOrThatTheExpertsHaveNotRankedYet() throws Exception {
        User user = new User();
        user.setEmail("ion@e-uvt.ro");
        var ecer = new RegistryLookupFacade.EntityLevel(RegistryKind.SCIENTIFIC_EVENT, "ECER 2024", "RANKED", "INTERNATIONAL",
                "COMISIA_28_CRITERIA", null, "CONFERENCE", List.of("PROCEEDINGS_LANGUAGE", "INTERNATIONAL_ORGANISER"), "Cyprus");
        var waiting = new RegistryLookupFacade.EntityLevel(RegistryKind.ORGANIZATION, "Asociația nouă", "AWAITING_RANK", null,
                null, null, null, List.of(), null);
        var rejected = new RegistryLookupFacade.EntityLevel(RegistryKind.AWARD, "Diplomă", "REJECTED", null, null,
                "Nu este un premiu.", null, List.of(), null);
        var facts = new RegistryLookupFacade.ListFacts("1234-5678",
                new RegistryScoringSupport.JournalFacts(false, true, true, true, java.util.Set.of("SSCI", "SCOPUS", "ERIH", "DBLP"), 1.25), "University of Helsinki",
                new RegistryScoringSupport.UniversityFacts(101, 115, "Finland"));
        when(lookup.levelsFor("ion@e-uvt.ro")).thenReturn(Map.of(
                "a1", new RegistryLookupFacade.RecordLevels(List.of(ecer), null),
                "a2", new RegistryLookupFacade.RecordLevels(List.of(waiting, rejected), facts)));

        mockMvc.perform(get("/user/workspace/activities/registry-levels").with(authenticated(user))
                        .cookie(new jakarta.servlet.http.Cookie("SCHOLARDEX_LANG", "ro")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.a1[0].entity").value("ECER 2024"))
                .andExpect(jsonPath("$.a1[0].text").value(containsString("Internațională")))
                .andExpect(jsonPath("$.a1[0].text").value(containsString("(a), (b)")))
                .andExpect(jsonPath("$.a2[0].text").value(containsString("neclasificată încă")))
                .andExpect(jsonPath("$.a2[1].text").value(containsString("Nu este un premiu.")))
                .andExpect(jsonPath("$.a2[2].entity").value("ISSN 1234-5678"))
                .andExpect(jsonPath("$.a2[2].text").value(containsString("Web of Science (SCIE, SSCI sau AHCI)")))
                .andExpect(jsonPath("$.a2[2].text").value(containsString("fără taxă de publicare")))
                .andExpect(jsonPath("$.a2[3].entity").value("University of Helsinki"))
                .andExpect(jsonPath("$.a2[3].text").value(containsString("URAP: locul 101")));
    }

    @Test
    void thePickerSuggestsNamesWithTheirLevelInTheReadersLanguage() throws Exception {
        User user = new User();
        user.setEmail("ion@e-uvt.ro");
        when(lookup.search(eq(RegistryKind.SCIENTIFIC_EVENT), any())).thenReturn(List.of(
                new RegistryLookupFacade.Suggestion("ECER 2024", "INTERNATIONAL", "Științe ale educației", "RANKED", null),
                new RegistryLookupFacade.Suggestion("ECER", null, null, "AWAITING_RANK", "ECER 2024")));

        mockMvc.perform(get("/api/entities/registry").param("kind", "SCIENTIFIC_EVENT").param("q", "ecer")
                        .with(authenticated(user)).cookie(new jakarta.servlet.http.Cookie("SCHOLARDEX_LANG", "en")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].levelLabel").value("International"))
                .andExpect(jsonPath("$[1].levelLabel").doesNotExist());
        mockMvc.perform(get("/api/entities/registry").param("kind", "NOT_A_REGISTRY").param("q", "ecer").with(authenticated(user)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutASignedInResearcherNothingIsRead() throws Exception {
        mockMvc.perform(get("/user/workspace/activities/registry-levels")).andExpect(status().isUnauthorized());
        verifyNoInteractions(lookup);
    }

    private static RequestPostProcessor authenticated(User user) {
        return request -> {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "RESEARCHER");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
