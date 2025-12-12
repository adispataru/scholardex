package ro.uvt.pokedex.core.service.scopus.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class AuthorWorksRequest {
    private String request_id;
    private String author_id;
    private String from_date;
    private Paging paging = new Paging();
    private boolean include_enrichment = true;
    private String format = "legacy";

    @Data
    public static class Paging {
        private Integer page_size;
        private String cursor;
    }
}
