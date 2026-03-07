package ro.uvt.pokedex.core.service.scopus.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AuthorWorksResponse {
    private String request_id;
    private String author_id;
    private String from_date;
    private Integer total;
    private String next_cursor;
    private List<Map<String, Object>> items;
}
