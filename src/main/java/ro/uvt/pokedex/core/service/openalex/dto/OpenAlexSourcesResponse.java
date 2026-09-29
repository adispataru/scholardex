package ro.uvt.pokedex.core.service.openalex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * The part of an OpenAlex {@code /sources} page the platform reads: where a venue is published. The source
 * embedded in a work ("dehydrated") does not carry the country; only the source entity does.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAlexSourcesResponse {
    private List<Source> results;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Source {
        private String id;                     // https://openalex.org/S...
        private String display_name;
        private String country_code;           // ISO 3166 alpha-2 of the host organization; often null
        private String host_organization_name;
    }
}
