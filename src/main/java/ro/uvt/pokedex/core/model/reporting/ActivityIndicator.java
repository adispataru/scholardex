package ro.uvt.pokedex.core.model.reporting;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "scholardex.activityIndicators")
public class ActivityIndicator {
    @Id
    private String id;
    private String name;
    private String formula;

    @DBRef
    private Domain domain;

}