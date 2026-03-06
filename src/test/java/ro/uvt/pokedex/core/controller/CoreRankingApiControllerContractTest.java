package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.ApiExceptionHandler;
import ro.uvt.pokedex.core.controller.dto.CoreRankingListItemResponse;
import ro.uvt.pokedex.core.controller.dto.CoreRankingPageResponse;
import ro.uvt.pokedex.core.service.application.CoreRankingQueryService;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CoreRankingApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class CoreRankingApiControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CoreRankingQueryService coreRankingQueryService;

    @Test
    void defaultRequestReturnsPagedEnvelope() throws Exception {
        when(coreRankingQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new CoreRankingPageResponse(
                        List.of(
                                item("1", "Conference A", "CA", "A"),
                                item("2", "Conference B", "CB", "B")
                        ),
                        0, 25, 2, 1
                ));

        mockMvc.perform(get("/api/rankings/core"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void pagingSortingAndDirectionAreApplied() throws Exception {
        when(coreRankingQueryService.search(1, 2, "acronym", "desc", null))
                .thenReturn(new CoreRankingPageResponse(
                        List.of(item("3", "Conference C", "CC", "C")),
                        1, 2, 3, 2
                ));

        mockMvc.perform(get("/api/rankings/core")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "acronym")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("3"));
    }

    @Test
    void queryMatchesConfiguredFields() throws Exception {
        when(coreRankingQueryService.search(0, 25, "name", "asc", "name-hit"))
                .thenReturn(new CoreRankingPageResponse(List.of(item("name-hit", "Name Hit", "NH", "A")), 0, 25, 1, 1));
        when(coreRankingQueryService.search(0, 25, "name", "asc", "acr-hit"))
                .thenReturn(new CoreRankingPageResponse(List.of(item("acr-hit", "Other", "ACR-HIT", "B")), 0, 25, 1, 1));
        when(coreRankingQueryService.search(0, 25, "name", "asc", "src-hit"))
                .thenReturn(new CoreRankingPageResponse(List.of(item("src-hit", "Other", "OS", "C")), 0, 25, 1, 1));

        mockMvc.perform(get("/api/rankings/core").param("q", "name-hit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("name-hit"));

        mockMvc.perform(get("/api/rankings/core").param("q", "acr-hit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("acr-hit"));

        mockMvc.perform(get("/api/rankings/core").param("q", "src-hit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("src-hit"));
    }

    @Test
    void invalidParamsReturnBadRequestEnvelope() throws Exception {
        when(coreRankingQueryService.search(0, 25, "bad", "asc", null))
                .thenThrow(new IllegalArgumentException("Invalid sort parameter. Allowed: name, acronym."));
        when(coreRankingQueryService.search(0, 25, "name", "up", null))
                .thenThrow(new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc."));
        when(coreRankingQueryService.search(0, 25, "name", "asc", "x".repeat(101)))
                .thenThrow(new IllegalArgumentException("Invalid q parameter. Maximum length is 100."));

        mockMvc.perform(get("/api/rankings/core").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/core").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/core").param("sort", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/core").param("direction", "up"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/core").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    private CoreRankingListItemResponse item(String id, String name, String acronym, String category2023) {
        return new CoreRankingListItemResponse(id, name, acronym, category2023);
    }
}
