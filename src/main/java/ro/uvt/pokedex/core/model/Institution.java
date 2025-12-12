package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.scopus.Affiliation;

import java.util.List;

@Data
@Document(collection = "institutions")
public class Institution {
    @Id
    private String name;
    private String description;
    private List<Affiliation> scopusAffiliations;
    private List<String> wosAffiliations;
}
