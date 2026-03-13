package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade;
import ro.uvt.pokedex.core.service.application.model.UserActivityInstancesViewModel;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ActivityInstanceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class ActivityInstanceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserActivityInstanceFacade userActivityInstanceFacade;

    @Test
    void canonicalActivitiesRouteRendersExpectedViewModel() throws Exception {
        User user = userPrincipal("u@uvt.ro", "r1");
        ActivityInstance activityInstance = new ActivityInstance();
        activityInstance.setId("a1");
        Activity activity = new Activity();
        activity.setName("A");
        activityInstance.setActivity(activity);

        when(userActivityInstanceFacade.buildActivityInstancesView(eq("r1")))
                .thenReturn(new UserActivityInstancesViewModel(
                        List.of(),
                        new Activity.ReferenceField[0],
                        new ActivityInstance(),
                        List.of(activityInstance),
                        List.of("L"),
                        List.of(1)
                ));

        mockMvc.perform(get("/user/activities").with(authenticatedUser(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("user/activity-instances"))
                .andExpect(model().attributeExists(
                        "activities",
                        "referenceTypes",
                        "activityInstances",
                        "activityLabels",
                        "activityData",
                        "newActivityInstance"));
    }

    @Test
    void canonicalActivitiesMutationsRedirectToCanonicalListRoute() throws Exception {
        mockMvc.perform(post("/user/activities/create"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/activities"));

        mockMvc.perform(post("/user/activities/update"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/activities"));

        mockMvc.perform(post("/user/activities/delete/{id}", "a1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/activities"));
    }

    @Test
    void activityEditAndFieldsUseCanonicalActivitiesFamily() throws Exception {
        ActivityInstance activityInstance = new ActivityInstance();
        activityInstance.setId("a1");
        Activity activity = new Activity();
        activity.setName("A");
        activityInstance.setActivity(activity);
        activityInstance.setFields(new java.util.HashMap<>());
        activityInstance.setReferenceFields(new java.util.HashMap<>());
        when(userActivityInstanceFacade.findActivityInstance("a1")).thenReturn(Optional.of(activityInstance));

        mockMvc.perform(get("/user/activities/edit/{id}", "a1"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/activity-instances-edit"))
                .andExpect(model().attributeExists("activityInstance"));

        Activity selectedActivity = new Activity();
        when(userActivityInstanceFacade.findActivity("act-1")).thenReturn(Optional.of(selectedActivity));
        mockMvc.perform(get("/user/activities/activity/{id}/fields", "act-1"))
                .andExpect(status().isOk());
    }

    @Test
    void legacyActivityInstancesAliasesAreRemoved() throws Exception {
        mockMvc.perform(get("/user/activityInstances").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/user/activityInstances/create").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/user/activityInstances/update").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/user/activityInstances/edit/{id}", "a1").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/user/activityInstances/delete/{id}", "a1").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/user/activityInstances/activity/{id}/fields", "a1").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedActivitiesRouteRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/user/activities"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    private User userPrincipal(String email, String researcherId) {
        User user = new User();
        user.setEmail(email);
        user.setResearcherId(researcherId);
        return user;
    }

    private RequestPostProcessor authenticatedUser(String email) {
        User user = new User();
        user.setEmail(email);
        return authenticatedUser(user);
    }

    private RequestPostProcessor authenticatedUser(User user) {
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
