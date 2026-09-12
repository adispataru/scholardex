package ro.uvt.pokedex.core.service.scopus.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H106 S5 — request for the Python service's reverse-title citation search ({@code /v1/citations/by-title}).
 * Keys are echoed back verbatim: a Scopus EID, {@code doi:<normalized doi>} or a canonical publication id —
 * see {@link ro.uvt.pokedex.core.service.importing.scopus.CitedWorkKey}.
 */
@Data
public class CitationsByTitleRequest {

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("items")
    private Map<String, CitedWorkSpec> items = new LinkedHashMap<>();

    @JsonProperty("page_size_per_item")
    private int pageSizePerItem = 25;

    @JsonProperty("include_enrichment")
    private boolean includeEnrichment = true;

    @JsonProperty("verify_references")
    private boolean verifyReferences = true;

    @Data
    public static class CitedWorkSpec {
        @JsonProperty("title")
        private String title;
        @JsonProperty("surnames")
        private List<String> surnames = new ArrayList<>();
        /** Lower bound on the citing document's cover date (YYYY-MM-DD), null/blank = none. */
        @JsonProperty("from_date")
        private String fromDate;
        @JsonProperty("known_citing_eids")
        private List<String> knownCitingEids = new ArrayList<>();
    }
}
