package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminResearcherProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminResearcherProfileControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void createProfileWithMissingFirstNameReturnsBadRequest() throws Exception {
        String body = """
                {
                  "firstName":"",
                  "lastName":"Doe"
                }
                """;

        mockMvc.perform(post("/api/admin/researcher-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateProfileWithMissingLastNameReturnsBadRequest() throws Exception {
        String body = """
                {
                  "firstName":"Jane",
                  "lastName":""
                }
                """;

        mockMvc.perform(put("/api/admin/researcher-profiles/{email}", "jane@uvt.ro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProfileWithValidPayloadReturnsOk() throws Exception {
        User saved = new User();
        saved.setEmail("jane@uvt.ro");
        when(userService.saveResearcherProfile(any(), any())).thenReturn(saved);

        String body = """
                {
                  "email":"jane@uvt.ro",
                  "firstName":"Jane",
                  "lastName":"Doe",
                  "scopusId":["a1"]
                }
                """;

        mockMvc.perform(post("/api/admin/researcher-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
