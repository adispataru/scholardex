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
        // Research-ethics gate: retracted works must not score (standard's Perspectiva a).
        private Boolean is_retracted;
        // Bibliographic detail for complete citations in the verification-sheet export.
        private Biblio biblio;
        // Field-weighted citation impact + normalized percentile (impact, Perspectiva c).
        private Double fwci;
        private CitationNormalizedPercentile citation_normalized_percentile;
        // Subject/domain signal (the "is it CS / which domain" routing).
        private PrimaryTopic primary_topic;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Biblio {
        private String volume;
        private String issue;
        private String first_page;
        private String last_page;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CitationNormalizedPercentile {
        private Double value;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrimaryTopic {
        private String id;
        private String display_name;
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
        private List<Institution> institutions;       // OpenAlex-resolved orgs (display_name + ROR)
        private List<String> raw_affiliation_strings;  // verbatim affiliation lines as printed on the paper
        private List<String> countries;                // ISO-3166 alpha-2 codes
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
    public static class Institution {
        private String id;                 // https://openalex.org/I...
        private String display_name;
        private String ror;                // https://ror.org/...
        private String country_code;       // ISO-3166 alpha-2
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
        // Publisher of the venue — the SENSE book/chapter classification key (clas.c keys book scoring on it);
        // OpenAlex book-series/ebook venues otherwise carry no publisher for the book scorer.
        private String host_organization_name;
        // OpenAlex venue type: journal | conference | repository | ebook platform | book series | metadata | other.
        // The authoritative "is this a conference / book series" signal — the work-level `type` is uniformly
        // "article" for both journals and conferences, so this is the only OpenAlex venue discriminator.
        private String type;
    }
}
