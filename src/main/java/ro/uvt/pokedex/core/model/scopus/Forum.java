package ro.uvt.pokedex.core.model.scopus;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "scopus.forums")
public class Forum {
    @Id
    private String id; // Consistent for the journal or conference series
    private String publicationName;
    private String issn;
    private String eIssn;
    private String isbn;
    private String aggregationType;
    private String publisher;
    private String scopusId;
    private boolean approved;
}
