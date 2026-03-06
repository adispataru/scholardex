package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scopus.author_search_view")
public class ScopusAuthorSearchView {
    @Id
    private String id;
    private String name;
    private List<String> affiliationIds = new ArrayList<>();
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
    private String sourceEventId;
}

