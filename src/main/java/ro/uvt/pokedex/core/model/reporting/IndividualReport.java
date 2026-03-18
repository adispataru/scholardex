package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.Institution;

@Data
@EqualsAndHashCode(callSuper = true)
@Document(collection = "individualReports")
public class IndividualReport extends AbstractReport {
    @DBRef
    private Institution individualAffiliation = null;
}
