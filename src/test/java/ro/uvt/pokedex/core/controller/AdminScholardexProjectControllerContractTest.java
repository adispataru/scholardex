package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.ApiExceptionHandler;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedProjectFact;
import ro.uvt.pokedex.core.service.brainmap.UserDefinedProjectService;
import ro.uvt.pokedex.core.service.cordis.CordisProject;
import ro.uvt.pokedex.core.service.cordis.CordisProjectClient;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AdminScholardexProjectController.class, properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/test")
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class AdminScholardexProjectControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserDefinedProjectService userDefinedProjectService;
    @MockitoBean
    private CordisProjectClient cordisProjectClient;
    @MockitoBean
    private ro.uvt.pokedex.core.service.brainmap.ProjectRebuildService projectRebuildService;

    @Test
    void createReturnsSavedProject() throws Exception {
        UserDefinedProjectFact saved = new UserDefinedProjectFact();
        saved.setId("101061610");
        saved.setEuGrantId("101061610");
        saved.setBudget(270000L);
        when(userDefinedProjectService.save(any(), any())).thenReturn(saved);

        mockMvc.perform(post("/api/admin/projects")
                        .contentType("application/json")
                        .content("{\"euGrantId\":\"101061610\",\"budget\":270000,\"origin\":\"CORDIS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("101061610"))
                .andExpect(jsonPath("$.budget").value(270000));
    }

    @Test
    void createWithoutKeyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/admin/projects")
                        .contentType("application/json")
                        .content("{\"budget\":270000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cordisPreviewReturnsProjectAndSuggestedUvtBudget() throws Exception {
        CordisProject project = new CordisProject("101017168", "232756", "SERRANO", "Title",
                "2021-01-01", "2023-12-31", "H2020", 4343180L, 4343180L,
                List.of(new CordisProject.CordisOrg("participant", "UNIVERSITATEA DE VEST DIN TIMISOARA",
                        "WUT", "RO", 216250L)));
        when(cordisProjectClient.fetch("101017168")).thenReturn(project);

        mockMvc.perform(get("/api/admin/projects/cordis/101017168"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.acronym").value("SERRANO"))
                .andExpect(jsonPath("$.suggestedOrgName").value("UNIVERSITATEA DE VEST DIN TIMISOARA"))
                .andExpect(jsonPath("$.suggestedBudget").value(216250));
    }

    @Test
    void cordisPreviewReturns502WhenFetchFails() throws Exception {
        when(cordisProjectClient.fetch("bad")).thenThrow(new IllegalStateException("unreachable"));
        mockMvc.perform(get("/api/admin/projects/cordis/bad")).andExpect(status().isBadGateway());
    }

    @Test
    void getByIdReturns404WhenAbsent() throws Exception {
        when(userDefinedProjectService.findById("missing")).thenReturn(null);
        mockMvc.perform(get("/api/admin/projects/missing")).andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns404WhenAbsent() throws Exception {
        when(userDefinedProjectService.delete(eq("missing"))).thenReturn(false);
        mockMvc.perform(delete("/api/admin/projects/missing")).andExpect(status().isNotFound());
    }
}
