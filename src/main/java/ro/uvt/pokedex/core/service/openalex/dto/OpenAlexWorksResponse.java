package ro.uvt.pokedex.core.service.openalex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * H66B Phase 4a — typed view of the OpenAlex {@code GET /works} response. Field names match the OpenAlex
 * JSON directly (snake_case Java fields, the same convention the Scopus-bridge DTOs use); every class ignores
 * the many unmapped OpenAlex fields. See https://docs.openalex.org/api-entities/works.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAlexWorksResponse {
    private Meta meta;
    private List<OpenAlexWork> results;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        private Integer count;
        private String next_cursor;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenAlexWork {
        private String id;                 // https://openalex.org/W...
        private String doi;                // https://doi.org/10...
        private String title;
        private String display_name;
        private Integer publication_year;
        private String type;
        private Integer cited_by_count;
        private OpenAccess open_access;
        private List<Authorship> authorships;
        private PrimaryLocation primary_location;
        private List<String> referenced_works;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenAccess {
        private Boolean is_oa;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Authorship {
        private Author author;
        private Boolean is_corresponding;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Author {
        private String id;
        private String display_name;
        private String orcid;              // https://orcid.org/...
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrimaryLocation {
        private OpenAlexSource source;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenAlexSource {
        private String id;
        private String display_name;
        private List<String> issn;
        private String issn_l;
    }
}
