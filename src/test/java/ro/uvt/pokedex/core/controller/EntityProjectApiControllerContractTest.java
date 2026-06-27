package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.ApiExceptionHandler;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectPageResponse;
import ro.uvt.pokedex.core.service.application.PostgresScholardexProjectReadPort;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = EntityProjectApiController.class, properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/test")
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class EntityProjectApiControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostgresScholardexProjectReadPort postgresScholardexProjectReadPort;

    private ScholardexProjectListItemResponse item(String id, String code, String funder, String title) {
        return new ScholardexProjectListItemResponse(id, code, null, title, funder, "Marius Paulescu", 2017, 2018, "UVT");
    }

    @Test
    void defaultRequestReturnsPagedEnvelope() throws Exception {
        when(postgresScholardexProjectReadPort.search(0, 25, "title", "asc", null))
                .thenReturn(new ScholardexProjectPageResponse(
                        List.of(item("sproj_1", "PN-III-X", "UEFISCDI", "Alpha"),
                                item("sproj_2", "Horizon-Y", "EC", "Beta")),
                        0, 25, 2, 1));

        mockMvc.perform(get("/api/entities/projects"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("sproj_1"))
                .andExpect(jsonPath("$.items[0].director").value("Marius Paulescu"))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void queryAndPagingApplied() throws Exception {
        when(postgresScholardexProjectReadPort.search(1, 5, "code", "desc", "uefiscdi"))
                .thenReturn(new ScholardexProjectPageResponse(List.of(item("sproj_3", "PN-Z", "UEFISCDI", "Gamma")), 1, 5, 6, 2));

        mockMvc.perform(get("/api/entities/projects")
                        .param("page", "1").param("size", "5").param("sort", "code").param("direction", "desc")
                        .param("q", "uefiscdi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].code").value("PN-Z"));
    }

    @Test
    void findByIdReturnsProjectOr404() throws Exception {
        when(postgresScholardexProjectReadPort.findById("sproj_1")).thenReturn(item("sproj_1", "PN-III-X", "UEFISCDI", "Alpha"));
        when(postgresScholardexProjectReadPort.findById("missing")).thenReturn(null);

        mockMvc.perform(get("/api/entities/projects/sproj_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PN-III-X"));

        mockMvc.perform(get("/api/entities/projects/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidParamsReturnBadRequestEnvelope() throws Exception {
        when(postgresScholardexProjectReadPort.search(0, 25, "bad", "asc", null))
                .thenThrow(new IllegalArgumentException("Invalid sort parameter. Allowed: title, code, funder, startYear."));

        mockMvc.perform(get("/api/entities/projects").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/entities/projects").param("size", "101"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/entities/projects").param("sort", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }
}
