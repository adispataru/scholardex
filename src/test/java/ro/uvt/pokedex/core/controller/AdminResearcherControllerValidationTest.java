package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.service.ResearcherService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminResearcherController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminResearcherControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearcherService researcherService;

    @Test
    void addResearcherWithMissingFirstNameReturnsBadRequest() throws Exception {
        String body = """
                {
                  "firstName":"",
                  "lastName":"Doe"
                }
                """;

        mockMvc.perform(post("/api/admin/researchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateResearcherWithMissingLastNameReturnsBadRequest() throws Exception {
        String body = """
                {
                  "firstName":"Jane",
                  "lastName":""
                }
                """;

        mockMvc.perform(put("/api/admin/researchers/{id}", "r1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addResearcherWithValidPayloadReturnsOk() throws Exception {
        Researcher saved = new Researcher();
        saved.setId("r1");
        when(researcherService.saveResearcher(any(Researcher.class))).thenReturn(saved);

        String body = """
                {
                  "firstName":"Jane",
                  "lastName":"Doe",
                  "scopusId":["a1"]
                }
                """;

        mockMvc.perform(post("/api/admin/researchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
