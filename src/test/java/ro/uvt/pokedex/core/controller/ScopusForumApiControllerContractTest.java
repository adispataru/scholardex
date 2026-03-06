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
import ro.uvt.pokedex.core.controller.dto.ScopusForumListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;
import ro.uvt.pokedex.core.service.application.ScopusForumQueryService;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScopusForumApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class ScopusForumApiControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScopusForumQueryService scopusForumQueryService;

    @Test
    void defaultRequestReturnsPagedEnvelope() throws Exception {
        when(scopusForumQueryService.search(0, 25, "publicationName", "asc", null))
                .thenReturn(new ScopusForumPageResponse(
                        List.of(
                                item("1", "ACM Digital Library", "1111-1111", "2222-2222", "Journal"),
                                item("2", "IEEE Xplore", "3333-3333", "4444-4444", "Conference Proceeding")
                        ),
                        0, 25, 2, 1
                ));

        mockMvc.perform(get("/api/scopus/forums"))
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
        when(scopusForumQueryService.search(1, 2, "issn", "desc", null))
                .thenReturn(new ScopusForumPageResponse(
                        List.of(item("3", "ScienceDirect", "9999-1111", "9999-2222", "Journal")),
                        1, 2, 3, 2
                ));

        mockMvc.perform(get("/api/scopus/forums")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "issn")
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
        when(scopusForumQueryService.search(0, 25, "publicationName", "asc", "ieee"))
                .thenReturn(new ScopusForumPageResponse(List.of(item("name-hit", "IEEE Access", "1111", "2222", "Journal")), 0, 25, 1, 1));
        when(scopusForumQueryService.search(0, 25, "publicationName", "asc", "7777"))
                .thenReturn(new ScopusForumPageResponse(List.of(item("issn-hit", "Elsevier", "7777-ABCD", "2222", "Journal")), 0, 25, 1, 1));
        when(scopusForumQueryService.search(0, 25, "publicationName", "asc", "efgh"))
                .thenReturn(new ScopusForumPageResponse(List.of(item("eissn-hit", "Nature", "1111", "9999-EFGH", "Journal")), 0, 25, 1, 1));
        when(scopusForumQueryService.search(0, 25, "publicationName", "asc", "conference"))
                .thenReturn(new ScopusForumPageResponse(List.of(item("agg-hit", "SIGIR", "1111", "2222", "Conference")), 0, 25, 1, 1));

        mockMvc.perform(get("/api/scopus/forums").param("q", "ieee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("name-hit"));

        mockMvc.perform(get("/api/scopus/forums").param("q", "7777"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("issn-hit"));

        mockMvc.perform(get("/api/scopus/forums").param("q", "efgh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("eissn-hit"));

        mockMvc.perform(get("/api/scopus/forums").param("q", "conference"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("agg-hit"));
    }

    @Test
    void invalidParamsReturnBadRequestEnvelope() throws Exception {
        when(scopusForumQueryService.search(0, 25, "bad", "asc", null))
                .thenThrow(new IllegalArgumentException("Invalid sort parameter. Allowed: publicationName, issn, eIssn, aggregationType."));
        when(scopusForumQueryService.search(0, 25, "publicationName", "up", null))
                .thenThrow(new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc."));
        when(scopusForumQueryService.search(0, 25, "publicationName", "asc", "x".repeat(101)))
                .thenThrow(new IllegalArgumentException("Invalid q parameter. Maximum length is 100."));

        mockMvc.perform(get("/api/scopus/forums").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/forums").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/forums").param("sort", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/forums").param("direction", "up"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/forums").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    private ScopusForumListItemResponse item(String id, String name, String issn, String eIssn, String aggregationType) {
        return new ScopusForumListItemResponse(id, name, issn, eIssn, aggregationType);
    }
}
