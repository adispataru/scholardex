package ro.uvt.pokedex.core.service.dblp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * H66B Phase 4b — DBLP publication search response ({@code /search/publ/api?format=json}). DBLP's JSON is quirky:
 * {@code hits.hit} is present only when there are matches, and {@code authors}/{@code ee} can be a single object or
 * an array (we don't read those here, so we ignore them). Only the fields we need are mapped.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DblpSearchResponse {

    private DblpResult result;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DblpResult {
        private DblpHits hits;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DblpHits {
        private String total; // DBLP returns counts as strings
        private List<DblpHit> hit;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DblpHit {
        private String score;
        private DblpInfo info;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DblpInfo {
        private String key;     // e.g. "conf/iccs/SmithJ19" — the dblp record key (series = conf/iccs)
        private String title;
        private String venue;   // human venue label, e.g. "ICCS (1)"
        private String year;
        private String type;    // e.g. "Conference and Workshop Papers", "Journal Articles"
        private String doi;
        private String url;      // dblp record url
    }
}
