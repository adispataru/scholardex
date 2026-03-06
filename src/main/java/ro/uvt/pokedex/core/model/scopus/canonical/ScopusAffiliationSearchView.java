package ro.uvt.pokedex.core.model.scopus.canonical;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "scopus.affiliation_search_view")
public class ScopusAffiliationSearchView {
    @Id
    private String id;
    private String name;
    private String city;
    private String country;
    private String buildVersion;
    private Instant buildAt;
    private Instant updatedAt;
    private String sourceEventId;
}

