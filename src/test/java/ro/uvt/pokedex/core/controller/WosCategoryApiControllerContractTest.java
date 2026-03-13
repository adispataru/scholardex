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
import ro.uvt.pokedex.core.controller.dto.WosCategoryListItemResponse;
import ro.uvt.pokedex.core.controller.dto.WosCategoryPageResponse;
import ro.uvt.pokedex.core.service.application.WosCategoryQueryService;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WosCategoryApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class WosCategoryApiControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WosCategoryQueryService wosCategoryQueryService;

    @Test
    void defaultRequestReturnsPagedEnvelope() throws Exception {
        when(wosCategoryQueryService.search(0, 25, "categoryName", "asc", null))
                .thenReturn(new WosCategoryPageResponse(
                        List.of(
                                item("Computer Science - SCIE", "Computer Science", "SCIE", 3, 2024),
                                item("Economics - SSCI", "Economics", "SSCI", 2, 2023)
                        ),
                        0, 25, 2, 1
                ));

        mockMvc.perform(get("/api/rankings/categories"))
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
        when(wosCategoryQueryService.search(1, 2, "latestYear", "desc", null))
                .thenReturn(new WosCategoryPageResponse(
                        List.of(item("Design - SCIE", "Design", "SCIE", 4, 2022)),
                        1, 2, 3, 2
                ));

        mockMvc.perform(get("/api/rankings/categories")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "latestYear")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].key").value("Design - SCIE"));
    }

    @Test
    void queryMatchesConfiguredFields() throws Exception {
        when(wosCategoryQueryService.search(0, 25, "categoryName", "asc", "computer"))
                .thenReturn(new WosCategoryPageResponse(List.of(item("Computer Science - SCIE", "Computer Science", "SCIE", 3, 2024)), 0, 25, 1, 1));
        when(wosCategoryQueryService.search(0, 25, "categoryName", "asc", "ssci"))
                .thenReturn(new WosCategoryPageResponse(List.of(item("Economics - SSCI", "Economics", "SSCI", 2, 2023)), 0, 25, 1, 1));

        mockMvc.perform(get("/api/rankings/categories").param("q", "computer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].key").value("Computer Science - SCIE"));

        mockMvc.perform(get("/api/rankings/categories").param("q", "ssci"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].key").value("Economics - SSCI"));
    }

    @Test
    void invalidParamsReturnBadRequestEnvelope() throws Exception {
        when(wosCategoryQueryService.search(0, 25, "bad", "asc", null))
                .thenThrow(new IllegalArgumentException("Invalid sort parameter. Allowed: categoryName, edition, journalCount, latestYear."));
        when(wosCategoryQueryService.search(0, 25, "categoryName", "up", null))
                .thenThrow(new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc."));
        when(wosCategoryQueryService.search(0, 25, "categoryName", "asc", "x".repeat(101)))
                .thenThrow(new IllegalArgumentException("Invalid q parameter. Maximum length is 100."));

        mockMvc.perform(get("/api/rankings/categories").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/categories").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/categories").param("sort", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/categories").param("direction", "up"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/categories").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    private WosCategoryListItemResponse item(String key, String categoryName, String edition, long journalCount, Integer latestYear) {
        return new WosCategoryListItemResponse(key, categoryName, edition, journalCount, latestYear);
    }
}
