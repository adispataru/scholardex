package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.Researcher;

import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.groups")
public class Group {
    @Id
    private String id;
    private String name;
    private String description;
    @DBRef
    private List<Domain> domains = new ArrayList<>();
    @DBRef
    private Institution institution;
    @DBRef
    private List<Researcher> researchers = new ArrayList<>();
}
