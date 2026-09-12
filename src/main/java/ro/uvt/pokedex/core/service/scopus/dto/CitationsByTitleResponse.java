package ro.uvt.pokedex.core.service.scopus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** H106 S5 — items are the legacy flat citing documents plus {@code cited_key}, {@code verified}, {@code matched_reference}. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CitationsByTitleResponse {

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("by_key")
    private Map<String, List<Map<String, Object>>> byKey;

    @JsonProperty("summary")
    private Map<String, Object> summary;
}
