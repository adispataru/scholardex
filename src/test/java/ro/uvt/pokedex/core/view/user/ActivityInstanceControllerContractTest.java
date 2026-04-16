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
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivityInstanceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class ActivityInstanceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserActivityInstanceFacade userActivityInstanceFacade;

    @Test
    void canonicalActivitiesRouteRedirectsToWorkspace() throws Exception {
        mockMvc.perform(get("/user/activities").with(authenticatedUser("u@uvt.ro")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace#activities"));
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
    void activityEditRedirectsToWorkspaceAndFieldsEndpointWorks() throws Exception {
        mockMvc.perform(get("/user/activities/edit/{id}", "a1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace#activities"));

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
    void unauthenticatedActivitiesRouteRedirectsToWorkspace() throws Exception {
        mockMvc.perform(get("/user/activities"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/user/workspace#activities"));
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
