package ro.uvt.pokedex.core.model.tasks;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@EqualsAndHashCode(callSuper = true)
@Data
@Document(collection = "scholardex.tasks.scopusPublicationUpdate")
public class ScopusPublicationUpdate extends Task {
    @Id
    private String id;
    private String scopusId;
}
