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
import ro.uvt.pokedex.core.controller.dto.WosRankingListItemResponse;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;
import ro.uvt.pokedex.core.service.application.WosRankingQueryService;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WosRankingApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class WosRankingApiControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WosRankingQueryService wosRankingQueryService;

    @Test
    void defaultRequestReturnsPagedEnvelope() throws Exception {
        when(wosRankingQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new WosRankingPageResponse(
                        List.of(
                                item("1", "ACM Journal", "1111-1111", "2222-2222", List.of("3333-3333")),
                                item("2", "IEEE Journal", "4444-4444", "5555-5555", List.of("6666-6666"))
                        ),
                        0, 25, 2, 1
                ));

        mockMvc.perform(get("/api/rankings/wos"))
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
        when(wosRankingQueryService.search(1, 2, "name", "asc", null))
                .thenReturn(new WosRankingPageResponse(
                        List.of(item("3", "C name", "3333", "2222", List.of())),
                        1, 2, 3, 2
                ));

        mockMvc.perform(get("/api/rankings/wos")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "name")
                        .param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("3"));
    }

    @Test
    void validNonDefaultSortsAreApplied() throws Exception {
        when(wosRankingQueryService.search(0, 25, "issn", "desc", null))
                .thenReturn(new WosRankingPageResponse(
                        List.of(item("issn-sort", "ISSN Journal", "9999-0000", "1000-0000", List.of())),
                        0, 25, 1, 1
                ));
        when(wosRankingQueryService.search(0, 25, "eIssn", "asc", null))
                .thenReturn(new WosRankingPageResponse(
                        List.of(item("eissn-sort", "eISSN Journal", "2000-0000", "0001-9999", List.of())),
                        0, 25, 1, 1
                ));

        mockMvc.perform(get("/api/rankings/wos")
                        .param("sort", "issn")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("issn-sort"));

        mockMvc.perform(get("/api/rankings/wos")
                        .param("sort", "eIssn")
                        .param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("eissn-sort"));
    }

    @Test
    void queryMatchesByNameIssnEIssnAndAlternativeIssn() throws Exception {
        when(wosRankingQueryService.search(0, 25, "name", "asc", "nature"))
                .thenReturn(new WosRankingPageResponse(List.of(item("name-hit", "Nature Journal", "1111", "2222", List.of())), 0, 25, 1, 1));
        when(wosRankingQueryService.search(0, 25, "name", "asc", "abcd"))
                .thenReturn(new WosRankingPageResponse(List.of(item("issn-hit", "Other", "7777-ABCD", "4444", List.of())), 0, 25, 1, 1));
        when(wosRankingQueryService.search(0, 25, "name", "asc", "efgh"))
                .thenReturn(new WosRankingPageResponse(List.of(item("eissn-hit", "Other2", "8888", "9999-EFGH", List.of())), 0, 25, 1, 1));
        when(wosRankingQueryService.search(0, 25, "name", "asc", "alt-0001"))
                .thenReturn(new WosRankingPageResponse(List.of(item("alt-hit", "Other3", "1234", "5678", List.of("ALT-0001"))), 0, 25, 1, 1));

        mockMvc.perform(get("/api/rankings/wos").param("q", "nature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("name-hit"));

        mockMvc.perform(get("/api/rankings/wos").param("q", "abcd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("issn-hit"));

        mockMvc.perform(get("/api/rankings/wos").param("q", "efgh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("eissn-hit"));

        mockMvc.perform(get("/api/rankings/wos").param("q", "alt-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("alt-hit"));
    }

    @Test
    void blankQueryIsAcceptedByApi() throws Exception {
        when(wosRankingQueryService.search(0, 25, "name", "asc", "   "))
                .thenReturn(new WosRankingPageResponse(
                        List.of(item("blank-q", "Blank Query Journal", "1000", "2000", List.of())),
                        0, 25, 1, 1
                ));

        mockMvc.perform(get("/api/rankings/wos").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("blank-q"));

        verify(wosRankingQueryService).search(0, 25, "name", "asc", "   ");
    }

    @Test
    void invalidParamsReturnBadRequestEnvelope() throws Exception {
        when(wosRankingQueryService.search(0, 25, "bad", "asc", null))
                .thenThrow(new IllegalArgumentException("Invalid sort parameter. Allowed: name, issn, eIssn."));
        when(wosRankingQueryService.search(0, 25, "name", "up", null))
                .thenThrow(new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc."));
        when(wosRankingQueryService.search(0, 25, "name", "asc", "x".repeat(101)))
                .thenThrow(new IllegalArgumentException("Invalid q parameter. Maximum length is 100."));

        mockMvc.perform(get("/api/rankings/wos").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/wos").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/wos").param("sort", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/wos").param("direction", "up"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/rankings/wos").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    private WosRankingListItemResponse item(String id, String name, String issn, String eIssn, List<String> altIssns) {
        return new WosRankingListItemResponse(id, name, issn, eIssn, altIssns);
    }
}
