package ro.uvt.pokedex.core.service.openalex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * H73 slice 1 — a single OpenAlex <b>institution</b> record from the bulk snapshot
 * ({@code data/openalex/institutions/*.gz}). Trimmed to the fields the affiliation backbone needs:
 * the ROR (canonical cross-source institution key), display name + multilingual aliases/acronyms (the
 * matcher inputs for slice 2), and geo (city/country). Snake_case fields mirror the OpenAlex JSON;
 * unknown properties are ignored.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAlexInstitutionRecord {
    private String id;                              // https://openalex.org/I...
    private String ror;                             // https://ror.org/...
    private String display_name;
    private List<String> display_name_alternatives; // multilingual aliases — the cross-language match linchpin
    private List<String> display_name_acronyms;
    private String country_code;                    // ISO-3166 alpha-2
    private Geo geo;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Geo {
        private String city;
        private String country;
        private String country_code;
    }
}
