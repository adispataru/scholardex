package ro.uvt.pokedex.core.service.scopus.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CitationsByEidResponse {
    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("by_eid")
    private Map<String, List<JsonNode>> byEid;

    @JsonProperty("per_eid_count")
    private Map<String, Integer> perEidCount;
}
