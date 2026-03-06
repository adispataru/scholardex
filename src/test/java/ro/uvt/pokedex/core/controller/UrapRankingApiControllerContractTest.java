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
import ro.uvt.pokedex.core.controller.dto.UrapRankingListItemResponse;
import ro.uvt.pokedex.core.controller.dto.UrapRankingPageResponse;
import ro.uvt.pokedex.core.service.application.UrapRankingQueryService;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrapRankingApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class UrapRankingApiControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrapRankingQueryService urapRankingQueryService;

    @Test
    void defaultRequestReturnsPagedEnvelope() throws Exception {
        when(urapRankingQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new UrapRankingPageResponse(
                        List.of(
                                item("University A", "University A", "RO", 2025, 1),
                                item("University B", "University B", "US", 2025, 2)
                        ),
                        0, 25, 2, 1
                ));

        mockMvc.perform(get("/api/rankings/urap"))
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
        when(urapRankingQueryService.search(1, 2, "country", "desc", null))
                .thenReturn(new UrapRankingPageResponse(
                        List.of(item("University C", "University C", "UK", 2024, 12)),
                        1, 2, 3, 2
                ));

        mockMvc.perform(get("/api/rankings/urap")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "country")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("University C"));
    }

    @Test
    void queryMatchesConfiguredFields() throws Exception {
        when(urapRankingQueryService.search(0, 25, "name", "asc", "uni-hit"))
                .thenReturn(new UrapRankingPageResponse(List.of(item("uni-hit", "Uni Hit", "RO", 2025, 10)), 0, 25, 1, 1));
        when(urapRankingQueryService.search(0, 25, "name", "asc", "country-hit"))
                .thenReturn(new UrapRankingPageResponse(List.of(item("country-hit", "Other", "COUNTRY-HIT", 2025, 11)), 0, 25, 1, 1));

        mockMvc.perform(get("/api/rankings/urap").param("q", "uni-hit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("uni-hit"));

        mockMvc.perform(get("/api/rankings/urap").param("q", "country-hit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("country-hit"));
    }

    @Test
    void invalidParamsReturnBadRequestEnvelope() throws Exception {
        when(urapRankingQueryService.search(0, 25, "bad", "asc", null))
                .thenThrow(new IllegalArgumentException("Invalid sort parameter. Allowed: name, country."));
        when(urapRankingQueryService.search(0, 25, "name", "up", null))
                .thenThrow(new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc."));
        when(urapRankingQueryService.search(0, 25, "name", "asc", "x".repeat(101)))
                .thenThrow(new IllegalArgumentException("Invalid q parameter. Maximum length is 100."));

        mockMvc.perform(get("/api/rankings/urap").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/urap").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/urap").param("sort", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/urap").param("direction", "up"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/urap").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    private UrapRankingListItemResponse item(String id, String name, String country, Integer year, Integer rank) {
        return new UrapRankingListItemResponse(id, name, country, year, rank, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0);
    }
}
