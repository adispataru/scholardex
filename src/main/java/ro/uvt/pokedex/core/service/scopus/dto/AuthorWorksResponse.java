package ro.uvt.pokedex.core.service.scopus.dto;


import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class AuthorWorksResponse {
    private String request_id;
    private String author_id;
    private String from_date;
    private Integer total;
    private String next_cursor;
    private List<JsonNode> items;
}
