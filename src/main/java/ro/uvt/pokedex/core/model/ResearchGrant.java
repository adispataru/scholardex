package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "scholardex.grants")
public class ResearchGrant {

    private static final String currency = "EUR"; // Assuming all grants are in EUR

    @Id
    private String id;  // Added id field for MongoDB document identification
    private String acronym;
    private String number;
    private String sponsor;
    private Long budget;
}

