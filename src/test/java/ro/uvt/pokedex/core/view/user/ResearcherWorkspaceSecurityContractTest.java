package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.model.workspace.WorkspacePreferences;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.WorkspacePreferencesRepository;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.application.PublicationAuthorshipDecisionService;
import ro.uvt.pokedex.core.service.application.PublicationWizardFacade;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.UserScopusTaskFacade;
import ro.uvt.pokedex.core.service.application.model.UserActivityInstancesViewModel;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;
import ro.uvt.pokedex.core.service.application.model.UserScopusTasksViewModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearcherWorkspaceController.class)
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class ResearcherWorkspaceSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserPublicationFacade userPublicationFacade;
    @MockitoBean
    private UserActivityInstanceFacade userActivityInstanceFacade;
    @MockitoBean
    private UserScopusTaskFacade userScopusTaskFacade;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.UserOpenAlexTaskFacade userOpenAlexTaskFacade;
    @MockitoBean
    private UserReportFacade userReportFacade;
    @MockitoBean
    private WorkspacePreferencesRepository workspacePreferencesRepository;
    @MockitoBean
    private PublicationWizardFacade publicationWizardFacade;
    @MockitoBean
    private PublicationAuthorshipDecisionService publicationAuthorshipDecisionService;
    @MockitoBean
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @MockitoBean
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.onboarding.ResearcherOnboardingService researcherOnboardingService;

    private User researcher;

    @BeforeEach
    void setUp() {
        researcher = new User();
        researcher.setEmail("u@uvt.ro");
        researcher.setRoles(Set.of(UserRole.RESEARCHER));

        when(userPublicationFacade.buildUserPublicationsView("u@uvt.ro"))
                .thenReturn(Optional.of(new UserPublicationsViewModel(
                        List.of(), 0, Map.of(), Map.of(), Map.of(), Map.of(),
                        0, 0, 0, 0, null, List.of()
                )));
        when(userActivityInstanceFacade.buildActivityInstancesView("u@uvt.ro"))
                .thenReturn(new UserActivityInstancesViewModel(
                        List.of(), Activity.ReferenceField.values(), new ActivityInstance(), List.of(), List.of(), List.of()
                ));
        when(userReportFacade.buildIndividualReportsListView("u@uvt.ro"))
                .thenReturn(new UserReportsListViewModel(List.of()));
        when(userScopusTaskFacade.buildTasksView("u@uvt.ro", "u@uvt.ro"))
                .thenReturn(new UserScopusTasksViewModel(researcher, List.of(), List.of()));
        WorkspacePreferences preferences = new WorkspacePreferences();
        preferences.setUserEmail("u@uvt.ro");
        when(workspacePreferencesRepository.findById("u@uvt.ro")).thenReturn(Optional.of(preferences));
    }

    @Test
    void workspacePageExposesCsrfMetaTagsForJsonFetches() throws Exception {
        mockMvc.perform(get("/user/workspace").with(user(researcher)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("name=\"_csrf_header\"")));
    }

    @Test
    void createActivityJsonEndpointAcceptsAuthenticatedCsrfProtectedPost() throws Exception {
        Activity activity = new Activity();
        activity.setId("act-1");
        activity.setName("Grant Cercetare");
        ActivityInstance saved = new ActivityInstance();
        saved.setId("inst-1");
        saved.setActivity(activity);
        saved.setResearcherId("u@uvt.ro");

        when(userActivityInstanceFacade.findActivity("act-1")).thenReturn(Optional.of(activity));
        when(userActivityInstanceFacade.saveActivityInstance(org.mockito.ArgumentMatchers.any(ActivityInstance.class))).thenReturn(saved);

        mockMvc.perform(post("/user/workspace/activities/create")
                        .with(user(researcher))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activityId":"act-1","name":"SERRANO","date":"2024-09-01","fields":{},"referenceFields":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("inst-1")));
    }
}
