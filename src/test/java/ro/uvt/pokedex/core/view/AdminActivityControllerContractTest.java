package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.service.application.ActivityManagementFacade;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminActivityController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminActivityControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivityManagementFacade activityManagementFacade;

    @Test
    void editActivityRendersSharedAdminFormBaseline() throws Exception {
        Activity activity = new Activity();
        activity.setId("a1");
        activity.setName("Workshop");

        Activity.Field field = new Activity.Field();
        field.setName("Audience");
        field.setAllowedValues(List.of("Local", "International"));
        field.setNumber(false);
        activity.setFields(List.of(field));
        activity.setReferenceFields(List.of(Activity.ReferenceField.EVENT_NAME));

        when(activityManagementFacade.findActivity("a1")).thenReturn(Optional.of(activity));

        mockMvc.perform(get("/admin/activities/edit/{id}", "a1"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/activities-edit"))
                .andExpect(content().string(containsString("id=\"activity-admin-form\"")))
                .andExpect(content().string(containsString("app-admin-form__header")))
                .andExpect(content().string(containsString("aria-label=\"Breadcrumb\"")))
                .andExpect(content().string(containsString("href=\"/admin/activities\"")))
                .andExpect(content().string(containsString("id=\"fieldsContainer\"")))
                .andExpect(content().string(containsString("id=\"referencedFieldsContainer\"")))
                .andExpect(content().string(containsString("name=\"referenceFields[0]\"")));
    }

    @Test
    void editActivityMissingActivityRedirectsToList() throws Exception {
        when(activityManagementFacade.findActivity("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/activities/edit/{id}", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/activities"));
    }
}
