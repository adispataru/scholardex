package ro.uvt.pokedex.core.model.scopus;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "scopus.funding")
public class Funding {

    @Id
    private String id;  // Added id field for MongoDB document identification
    private String acronym;
    private String number;
    private String sponsor;
}

