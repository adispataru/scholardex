package ro.uvt.pokedex.core.service.scopus.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
public class CitationsByEidRequest {
    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("eid_last_date")
    private Map<String, String> eidLastDate; // eid -> last citation date (YYYY-MM-DD) or null

    @JsonProperty("page_size_per_eid")
    private int pageSizePerEid = 50;

    @JsonProperty("include_enrichment")
    private boolean includeEnrichment = true;
}
