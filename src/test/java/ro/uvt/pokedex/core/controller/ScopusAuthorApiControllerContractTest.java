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
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;
import ro.uvt.pokedex.core.service.application.ScholardexAuthorQueryService;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScopusAuthorApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class ScopusAuthorApiControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScholardexAuthorQueryService scholardexAuthorQueryService;

    @Test
    void defaultRequestReturnsPagedEnvelope() throws Exception {
        when(scholardexAuthorQueryService.search("60000434", 0, 25, "name", "asc", null))
                .thenReturn(new ScopusAuthorPageResponse(
                        List.of(
                                item("1", "Alice", List.of("UVT")),
                                item("2", "Bob", List.of("MIT", "Stanford"))
                        ),
                        0, 25, 2, 1
                ));

        mockMvc.perform(get("/api/scopus/authors"))
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
        when(scholardexAuthorQueryService.search("af-1", 1, 2, "id", "desc", null))
                .thenReturn(new ScopusAuthorPageResponse(List.of(item("3", "Carol", List.of())), 1, 2, 3, 2));

        mockMvc.perform(get("/api/scopus/authors")
                        .param("afid", "af-1")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "id")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].id").value("3"));
    }

    @Test
    void queryMatchesConfiguredFields() throws Exception {
        when(scholardexAuthorQueryService.search("60000434", 0, 25, "name", "asc", "alice"))
                .thenReturn(new ScopusAuthorPageResponse(List.of(item("name-hit", "Alice", List.of())), 0, 25, 1, 1));
        when(scholardexAuthorQueryService.search("60000434", 0, 25, "name", "asc", "0001"))
                .thenReturn(new ScopusAuthorPageResponse(List.of(item("id-hit", "Author", List.of())), 0, 25, 1, 1));

        mockMvc.perform(get("/api/scopus/authors").param("q", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("name-hit"));

        mockMvc.perform(get("/api/scopus/authors").param("q", "0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("id-hit"));
    }

    @Test
    void invalidParamsReturnBadRequestEnvelope() throws Exception {
        when(scholardexAuthorQueryService.search("60000434", 0, 25, "bad", "asc", null))
                .thenThrow(new IllegalArgumentException("Invalid sort parameter. Allowed: name, id."));
        when(scholardexAuthorQueryService.search("60000434", 0, 25, "name", "up", null))
                .thenThrow(new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc."));
        when(scholardexAuthorQueryService.search("60000434", 0, 25, "name", "asc", "x".repeat(101)))
                .thenThrow(new IllegalArgumentException("Invalid q parameter. Maximum length is 100."));

        mockMvc.perform(get("/api/scopus/authors").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/authors").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/authors").param("sort", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/authors").param("direction", "up"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/authors").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    private ScopusAuthorListItemResponse item(String id, String name, List<String> affiliations) {
        return new ScopusAuthorListItemResponse(id, name, affiliations);
    }
}
