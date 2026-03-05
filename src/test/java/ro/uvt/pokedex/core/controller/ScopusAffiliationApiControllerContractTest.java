package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.ApiExceptionHandler;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;
import ro.uvt.pokedex.core.service.application.ScopusAffiliationQueryService;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScopusAffiliationApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class ScopusAffiliationApiControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScopusAffiliationQueryService scopusAffiliationQueryService;

    @Test
    void defaultRequestReturnsPagedEnvelope() throws Exception {
        when(scopusAffiliationQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new ScopusAffiliationPageResponse(
                        List.of(
                                item("1", "UVT", "Timisoara", "Romania"),
                                item("2", "MIT", "Cambridge", "USA")
                        ),
                        0, 25, 2, 1
                ));

        mockMvc.perform(get("/api/scopus/affiliations"))
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
        when(scopusAffiliationQueryService.search(1, 2, "afid", "desc", null))
                .thenReturn(new ScopusAffiliationPageResponse(List.of(item("3", "EPFL", "Lausanne", "Switzerland")), 1, 2, 3, 2));

        mockMvc.perform(get("/api/scopus/affiliations")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "afid")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].afid").value("3"));
    }

    @Test
    void queryMatchesConfiguredFields() throws Exception {
        when(scopusAffiliationQueryService.search(0, 25, "name", "asc", "uvt"))
                .thenReturn(new ScopusAffiliationPageResponse(List.of(item("name-hit", "UVT", "Timisoara", "Romania")), 0, 25, 1, 1));
        when(scopusAffiliationQueryService.search(0, 25, "name", "asc", "0001"))
                .thenReturn(new ScopusAffiliationPageResponse(List.of(item("id-hit", "Other", "City", "Country")), 0, 25, 1, 1));
        when(scopusAffiliationQueryService.search(0, 25, "name", "asc", "timisoara"))
                .thenReturn(new ScopusAffiliationPageResponse(List.of(item("city-hit", "Other", "Timisoara", "Country")), 0, 25, 1, 1));
        when(scopusAffiliationQueryService.search(0, 25, "name", "asc", "romania"))
                .thenReturn(new ScopusAffiliationPageResponse(List.of(item("country-hit", "Other", "City", "Romania")), 0, 25, 1, 1));

        mockMvc.perform(get("/api/scopus/affiliations").param("q", "uvt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].afid").value("name-hit"));

        mockMvc.perform(get("/api/scopus/affiliations").param("q", "0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].afid").value("id-hit"));

        mockMvc.perform(get("/api/scopus/affiliations").param("q", "timisoara"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].afid").value("city-hit"));

        mockMvc.perform(get("/api/scopus/affiliations").param("q", "romania"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].afid").value("country-hit"));
    }

    @Test
    void invalidParamsReturnBadRequestEnvelope() throws Exception {
        when(scopusAffiliationQueryService.search(0, 25, "bad", "asc", null))
                .thenThrow(new IllegalArgumentException("Invalid sort parameter. Allowed: name, afid, city, country."));
        when(scopusAffiliationQueryService.search(0, 25, "name", "up", null))
                .thenThrow(new IllegalArgumentException("Invalid direction parameter. Allowed: asc, desc."));
        when(scopusAffiliationQueryService.search(0, 25, "name", "asc", "x".repeat(101)))
                .thenThrow(new IllegalArgumentException("Invalid q parameter. Maximum length is 100."));

        mockMvc.perform(get("/api/scopus/affiliations").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/affiliations").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/affiliations").param("sort", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/affiliations").param("direction", "up"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(get("/api/scopus/affiliations").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    private ScopusAffiliationListItemResponse item(String afid, String name, String city, String country) {
        return new ScopusAffiliationListItemResponse(afid, name, city, country);
    }
}
