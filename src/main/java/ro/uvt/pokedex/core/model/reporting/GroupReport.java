package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import ro.uvt.pokedex.core.model.scopus.Affiliation;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Document(collection = "groupReports")
public class GroupReport extends AbstractReport {
    @DBRef
    private List<Group> groups = new ArrayList<>();
    @DBRef
    private List<Affiliation> affiliations = new ArrayList<>();
}
