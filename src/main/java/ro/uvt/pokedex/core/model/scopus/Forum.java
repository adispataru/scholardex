package ro.uvt.pokedex.core.model.scopus;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "scopus.forums")
public class Forum {
    @Id
    private String id; // Consistent for the journal or conference series
    @Indexed
    private String publicationName;
    @Indexed
    private String issn;
    @Indexed
    private String eIssn;
    private String isbn;
    @Indexed
    private String aggregationType;
    private String publisher;
    private String scopusId;
    private boolean approved;
}
