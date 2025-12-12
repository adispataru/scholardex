package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "domains")
public class Domain {
    @Id
    private String name;
    private String description;
    private List<String> wosCategories = new ArrayList<>();
}
