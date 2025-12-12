package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.reporting.Position;

import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "scholardex.researchers")
public class Researcher {
    @Id
    private String id;
    private String firstName;
    private String lastName;
    private String scholarId;
    private List<String> scopusId = new ArrayList<>();
    private List<String> wosId = new ArrayList<>();
    private Position position;

    public String getName(){
        return firstName + " " + lastName;
    }
    // Getters and Setters
}
