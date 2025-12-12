package ro.uvt.pokedex.core.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "scholardex.cncsisList")
public class CNCSISPublisher {
    @Id
    private Long cncsisId;
    private String name;
    private String city;
    private String webpage;
}

